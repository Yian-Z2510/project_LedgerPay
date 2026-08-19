package com.ledgerpay.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class PaymentIdempotencyPostgresAcceptanceTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> merchantIds = new ArrayList<>();

    @AfterEach
    void removePersistedTestData() {
        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void sequentialExactReplayReturnsOnePayment() {
        Merchant merchant = createMerchant("Sequential Replay");
        MerchantOrder order = createOrder(merchant);

        PaymentCreationResult created = paymentService.createPayment(
                merchant, order.getId(), "sequential-replay-key");
        PaymentCreationResult replayed = paymentService.createPayment(
                merchant, order.getId(), "sequential-replay-key");

        assertTrue(!created.replayed());
        assertTrue(replayed.replayed());
        assertEquals(created.payment().getId(), replayed.payment().getId());
        assertEquals(1L, countPayments(merchant.getId(), "sequential-replay-key"));
    }

    @Test
    void replayReturnsCurrentFailedRepresentation() {
        Merchant merchant = createMerchant("Terminal Replay");
        MerchantOrder order = createOrder(merchant);
        PaymentCreationResult created = paymentService.createPayment(
                merchant, order.getId(), "terminal-replay-key");

        paymentService.simulatePayment(
                merchant,
                created.payment().getId(),
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PAYMENT_DECLINED);

        PaymentCreationResult replayed = paymentService.createPayment(
                merchant, order.getId(), "terminal-replay-key");
        PaymentDatabaseState databaseState = loadPaymentState(created.payment().getId());

        assertTrue(replayed.replayed());
        assertEquals(created.payment().getId(), replayed.payment().getId());
        assertEquals(PaymentStatus.FAILED, replayed.payment().getStatus());
        assertEquals(PaymentFailureCode.PAYMENT_DECLINED, replayed.payment().getFailureCode());
        assertNotNull(replayed.payment().getCompletedAt());
        assertEquals("FAILED", databaseState.status());
        assertEquals("PAYMENT_DECLINED", databaseState.failureCode());
        assertNotNull(databaseState.completedAt());
        assertEquals(1L, countPayments(merchant.getId(), "terminal-replay-key"));
    }

    @Test
    void historicalReplaySurvivesLaterOrderBecomingPaid() {
        Merchant merchant = createMerchant("Historical Replay");
        MerchantOrder order = createOrder(merchant);
        PaymentCreationResult historical = paymentService.createPayment(
                merchant, order.getId(), "historical-key-a");
        paymentService.simulatePayment(
                merchant,
                historical.payment().getId(),
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PROCESSING_ERROR);

        PaymentCreationResult laterAttempt = paymentService.createPayment(
                merchant, order.getId(), "historical-key-b");
        paymentService.simulatePayment(
                merchant,
                laterAttempt.payment().getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        PaymentCreationResult replayed = paymentService.createPayment(
                merchant, order.getId(), "historical-key-a");

        assertTrue(replayed.replayed());
        assertEquals(historical.payment().getId(), replayed.payment().getId());
        assertEquals(PaymentStatus.FAILED, replayed.payment().getStatus());
        assertEquals(PaymentFailureCode.PROCESSING_ERROR, replayed.payment().getFailureCode());
        assertEquals("PAID", loadOrderStatus(order.getId()));
        assertEquals(2L, countPaymentsForOrder(order.getId()));
    }

    @Test
    void sameMerchantAndKeyWithDifferentOrderConflictsSequentially() {
        Merchant merchant = createMerchant("Sequential Conflict");
        MerchantOrder firstOrder = createOrder(merchant);
        MerchantOrder secondOrder = createOrder(merchant);

        PaymentCreationResult created = paymentService.createPayment(
                merchant, firstOrder.getId(), "sequential-conflict-key");

        assertInstanceOf(
                IdempotencyConflictException.class,
                captureFailure(() -> paymentService.createPayment(
                        merchant,
                        secondOrder.getId(),
                        "sequential-conflict-key")));
        assertTrue(!created.replayed());
        assertEquals(1L, countPayments(merchant.getId(), "sequential-conflict-key"));
        assertEquals(firstOrder.getId(), loadWinnerOrderId(
                merchant.getId(), "sequential-conflict-key"));
    }

    @Test
    void differentMerchantsCanReuseSameKey() {
        Merchant firstMerchant = createMerchant("First Shared Key");
        Merchant secondMerchant = createMerchant("Second Shared Key");
        MerchantOrder firstOrder = createOrder(firstMerchant);
        MerchantOrder secondOrder = createOrder(secondMerchant);

        PaymentCreationResult first = paymentService.createPayment(
                firstMerchant, firstOrder.getId(), "merchant-shared-key");
        PaymentCreationResult second = paymentService.createPayment(
                secondMerchant, secondOrder.getId(), "merchant-shared-key");

        assertTrue(!first.replayed());
        assertTrue(!second.replayed());
        assertEquals(1L, countPayments(firstMerchant.getId(), "merchant-shared-key"));
        assertEquals(1L, countPayments(secondMerchant.getId(), "merchant-shared-key"));
    }

    @Test
    void concurrentSameKeyAndOrderCreatesOnceAndReplaysOnce() throws Exception {
        Merchant merchant = createMerchant("Concurrent Replay");
        MerchantOrder order = createOrder(merchant);
        String idempotencyKey = "concurrent-same-order-key";
        coordinateLookups(Set.of(idempotencyKey), 2);

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> paymentService.createPayment(merchant, order.getId(), idempotencyKey),
                () -> paymentService.createPayment(merchant, order.getId(), idempotencyKey));

        assertEquals(
                1L,
                outcomes.stream().filter(ConcurrentOutcome::created).count(),
                outcomes.toString());
        assertEquals(1L, outcomes.stream().filter(ConcurrentOutcome::replayed).count());
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure() == null));
        assertEquals(1L, countPayments(merchant.getId(), idempotencyKey));
        assertEquals(1L, countPaymentsForOrder(order.getId()));
        assertEquals(order.getId(), loadWinnerOrderId(merchant.getId(), idempotencyKey));
    }

    @Test
    void concurrentSameKeyAndDifferentOrdersRecoversLoserAsConflict() throws Exception {
        Merchant merchant = createMerchant("Concurrent Conflict");
        MerchantOrder firstOrder = createOrder(merchant);
        MerchantOrder secondOrder = createOrder(merchant);
        String idempotencyKey = "concurrent-different-order-key";
        coordinateLookups(Set.of(idempotencyKey), 4);

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> paymentService.createPayment(merchant, firstOrder.getId(), idempotencyKey),
                () -> paymentService.createPayment(merchant, secondOrder.getId(), idempotencyKey));

        assertEquals(
                1L,
                outcomes.stream().filter(ConcurrentOutcome::created).count(),
                outcomes.toString());
        assertEquals(1L, outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof IdempotencyConflictException)
                .count());
        assertEquals(1L, countPayments(merchant.getId(), idempotencyKey));
        UUID winnerOrderId = loadWinnerOrderId(merchant.getId(), idempotencyKey);
        assertTrue(winnerOrderId.equals(firstOrder.getId())
                || winnerOrderId.equals(secondOrder.getId()));
        assertEquals(1L, countPaymentsForOrder(winnerOrderId));
    }

    @Test
    void concurrentDifferentKeysAndSameOrderPreservesPendingRule() throws Exception {
        Merchant merchant = createMerchant("Concurrent Pending");
        MerchantOrder order = createOrder(merchant);
        String firstKey = "concurrent-first-key";
        String secondKey = "concurrent-second-key";
        coordinateLookups(Set.of(firstKey, secondKey), 2);

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> paymentService.createPayment(merchant, order.getId(), firstKey),
                () -> paymentService.createPayment(merchant, order.getId(), secondKey));

        assertEquals(
                1L,
                outcomes.stream().filter(ConcurrentOutcome::created).count(),
                outcomes.toString());
        assertEquals(1L, outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof PaymentAlreadyPendingException)
                .count());
        assertEquals(0L, outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof IdempotencyConflictException)
                .count());
        assertEquals(1L, countPaymentsForOrder(order.getId()));
        assertEquals(1L, countPendingPaymentsForOrder(order.getId()));
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
        merchantIds.add(merchant.getId());
        return merchant;
    }

    private MerchantOrder createOrder(Merchant merchant) {
        return orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
    }

    private void coordinateLookups(Set<String> idempotencyKeys, int lookupCount) {
        AtomicInteger coordinatedCalls = new AtomicInteger();
        CyclicBarrier queryCompletedBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            UUID merchantId = invocation.getArgument(0);
            String idempotencyKey = invocation.getArgument(1);
            Optional<Payment> result = loadPayment(merchantId, idempotencyKey);
            int callNumber = coordinatedCalls.incrementAndGet();
            if (idempotencyKeys.contains(idempotencyKey) && callNumber <= lookupCount) {
                queryCompletedBarrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        })
                .when(paymentRepository)
                .findByMerchantIdAndIdempotencyKey(any(UUID.class), any(String.class));
    }

    private Optional<Payment> loadPayment(UUID merchantId, String idempotencyKey) {
        List<UUID> paymentIds = jdbcTemplate.query(
                """
                SELECT id
                FROM payment
                WHERE merchant_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                merchantId,
                idempotencyKey);
        return paymentIds.stream().findFirst().flatMap(paymentRepository::findById);
    }

    private List<ConcurrentOutcome> runConcurrently(
            Supplier<PaymentCreationResult> firstOperation,
            Supplier<PaymentCreationResult> secondOperation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<ConcurrentOutcome> first = executor.submit(
                    () -> runAfterStartSignal(firstOperation, ready, start));
            Future<ConcurrentOutcome> second = executor.submit(
                    () -> runAfterStartSignal(secondOperation, ready, start));
            assertTrue(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS));
        }
    }

    private ConcurrentOutcome runAfterStartSignal(
            Supplier<PaymentCreationResult> operation,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new ConcurrentOutcome(operation.get(), null);
        } catch (RuntimeException exception) {
            return new ConcurrentOutcome(null, exception);
        }
    }

    private RuntimeException captureFailure(Supplier<PaymentCreationResult> operation) {
        try {
            operation.get();
            throw new AssertionError("Expected payment creation to fail.");
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private long countPayments(UUID merchantId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE merchant_id = ? AND idempotency_key = ?",
                Long.class,
                merchantId,
                idempotencyKey);
    }

    private long countPaymentsForOrder(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE merchant_order_id = ?",
                Long.class,
                orderId);
    }

    private long countPendingPaymentsForOrder(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payment
                WHERE merchant_order_id = ? AND status = 'PENDING'
                """,
                Long.class,
                orderId);
    }

    private UUID loadWinnerOrderId(UUID merchantId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                """
                SELECT merchant_order_id
                FROM payment
                WHERE merchant_id = ? AND idempotency_key = ?
                """,
                UUID.class,
                merchantId,
                idempotencyKey);
    }

    private String loadOrderStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_order WHERE id = ?",
                String.class,
                orderId);
    }

    private PaymentDatabaseState loadPaymentState(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status, failure_code, completed_at
                FROM payment
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new PaymentDatabaseState(
                        resultSet.getString("status"),
                        resultSet.getString("failure_code"),
                        toInstant(resultSet.getTimestamp("completed_at"))),
                paymentId);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ConcurrentOutcome(
            PaymentCreationResult result,
            RuntimeException failure) {

        boolean created() {
            return result != null && !result.replayed();
        }

        boolean replayed() {
            return result != null && result.replayed();
        }
    }

    private record PaymentDatabaseState(
            String status,
            String failureCode,
            Instant completedAt) {
    }
}
