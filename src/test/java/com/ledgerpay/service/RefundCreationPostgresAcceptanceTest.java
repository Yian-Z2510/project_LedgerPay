package com.ledgerpay.service;

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

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.InsufficientRefundableAmountException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefundCreationPostgresAcceptanceTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @MockitoSpyBean
    private RefundRepository refundRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> merchantIds = new ArrayList<>();

    @AfterEach
    void removePersistedTestData() {
        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM refund WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void acceptedRefundPersistsPendingRecordAndPaymentReservationTogether() {
        Merchant merchant = createMerchant("Accepted Refund");
        Payment payment = createSucceededPayment(merchant);

        RefundCreationResult result = refundService.createRefund(
                merchant,
                payment.getId(),
                request(300L),
                "accepted-refund-key");

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        var reloadedRefund = refundRepository.findById(result.refund().getId()).orElseThrow();
        assertFalse(result.replayed());
        assertEquals(RefundStatus.PENDING, reloadedRefund.getStatus());
        assertEquals(300L, reloadedRefund.getAmount());
        assertEquals(payment.getId(), reloadedRefund.getPayment().getId());
        assertEquals(300L, reloadedPayment.getPendingRefundAmount());
        assertEquals(0L, reloadedPayment.getRefundedAmount());
        assertEquals(
                OrderStatus.PAID,
                orderRepository.findById(payment.getOrder().getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void exactReplayStillSucceedsAfterRefundCapacityIsFullyReserved() {
        Merchant merchant = createMerchant("Full Capacity Replay");
        Payment payment = createSucceededPayment(merchant);
        CreateRefundRequest request = request(1000L);

        RefundCreationResult created = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                "full-capacity-replay-key");
        RefundCreationResult replayed = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                "full-capacity-replay-key");

        assertFalse(created.replayed());
        assertTrue(replayed.replayed());
        assertEquals(created.refund().getId(), replayed.refund().getId());
        assertEquals(1L, countRefunds(merchant.getId(), "full-capacity-replay-key"));
        assertEquals(
                1000L,
                paymentRepository.findById(payment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
    }

    @Test
    void failureAfterRefundFlushRollsBackRefundAndPaymentReservation() {
        Merchant merchant = createMerchant("Rollback Refund");
        Payment payment = createSucceededPayment(merchant);
        doThrow(new IllegalStateException("forced payment persistence failure"))
                .when(paymentRepository)
                .saveAndFlush(argThat(candidate -> candidate.getId().equals(payment.getId())
                        && candidate.getPendingRefundAmount() == 300L));

        assertThrows(
                IllegalStateException.class,
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(300L),
                        "rollback-refund-key"));

        assertEquals(0L, countRefunds(merchant.getId(), "rollback-refund-key"));
        assertEquals(
                0L,
                paymentRepository.findById(payment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
    }

    @Test
    void concurrentRefundsExceedingCapacityAllowExactlyOneReservation() throws Exception {
        Merchant merchant = createMerchant("Concurrent Over Capacity Refund");
        Payment payment = createSucceededPayment(merchant);
        coordinateOwnedPaymentChecks(payment, merchant);

        List<ConcurrentRefundOutcome> outcomes = runConcurrently(
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(700L),
                        "over-capacity-refund-key-a"),
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(600L),
                        "over-capacity-refund-key-b"));

        assertEquals(1L, outcomes.stream().filter(ConcurrentRefundOutcome::created).count());
        assertEquals(1L, outcomes.stream()
                .filter(outcome -> outcome.failure()
                        instanceof InsufficientRefundableAmountException)
                .count());
        ConcurrentRefundOutcome failedOutcome = outcomes.stream()
                .filter(outcome -> outcome.failure() != null)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(
                InsufficientRefundableAmountException.class,
                failedOutcome.failure());

        RefundCreationResult successfulResult = outcomes.stream()
                .map(ConcurrentRefundOutcome::result)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(1L, countRefundsForPayment(payment.getId()));
        assertEquals(
                successfulResult.refund().getAmount(),
                reloadedPayment.getPendingRefundAmount());
        assertEquals(0L, reloadedPayment.getRefundedAmount());
        assertTrue(reloadedPayment.getRefundedAmount()
                + reloadedPayment.getPendingRefundAmount()
                <= reloadedPayment.getAmount());
    }

    @Test
    void concurrentRefundsWithinCapacityReserveBothAmountsWithoutLostUpdate() throws Exception {
        Merchant merchant = createMerchant("Concurrent Valid Refunds");
        Payment payment = createSucceededPayment(merchant);
        coordinateOwnedPaymentChecks(payment, merchant);

        List<ConcurrentRefundOutcome> outcomes = runConcurrently(
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(300L),
                        "valid-concurrent-refund-key-a"),
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(400L),
                        "valid-concurrent-refund-key-b"));

        assertTrue(outcomes.stream().allMatch(ConcurrentRefundOutcome::created));
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure() == null));
        assertTrue(outcomes.stream().allMatch(outcome -> !outcome.result().replayed()));

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(2L, countRefundsForPayment(payment.getId()));
        assertEquals(2L, countPendingRefundsForPayment(payment.getId()));
        assertEquals(700L, reloadedPayment.getPendingRefundAmount());
        assertEquals(0L, reloadedPayment.getRefundedAmount());
        assertEquals(
                300L,
                reloadedPayment.getAmount()
                        - reloadedPayment.getRefundedAmount()
                        - reloadedPayment.getPendingRefundAmount());
    }

    @Test
    void concurrentSameKeyAndIdentityCreatesOnceAndReplaysWinner() throws Exception {
        Merchant merchant = createMerchant("Concurrent Refund Replay");
        Payment payment = createSucceededPayment(merchant);
        String idempotencyKey = "concurrent-refund-replay-key";
        coordinateRefundLookups(Set.of(idempotencyKey), 2);

        List<ConcurrentRefundOutcome> outcomes = runConcurrently(
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(300L),
                        idempotencyKey),
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(300L),
                        idempotencyKey));

        assertEquals(1L, outcomes.stream().filter(ConcurrentRefundOutcome::created).count());
        assertEquals(1L, outcomes.stream().filter(ConcurrentRefundOutcome::replayed).count());
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure() == null));
        assertEquals(
                1L,
                outcomes.stream()
                        .map(ConcurrentRefundOutcome::result)
                        .map(result -> result.refund().getId())
                        .distinct()
                        .count());
        assertEquals(1L, countRefunds(merchant.getId(), idempotencyKey));
        assertEquals(
                300L,
                paymentRepository.findById(payment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
    }

    @Test
    void concurrentSameKeyAndDifferentIdentityRecoversLoserAsConflict() throws Exception {
        Merchant merchant = createMerchant("Concurrent Refund Conflict");
        Payment firstPayment = createSucceededPayment(merchant);
        Payment secondPayment = createSucceededPayment(merchant);
        String idempotencyKey = "concurrent-refund-conflict-key";
        coordinateRefundLookups(Set.of(idempotencyKey), 4);

        List<ConcurrentRefundOutcome> outcomes = runConcurrently(
                () -> refundService.createRefund(
                        merchant,
                        firstPayment.getId(),
                        request(300L),
                        idempotencyKey),
                () -> refundService.createRefund(
                        merchant,
                        secondPayment.getId(),
                        request(400L),
                        idempotencyKey));

        assertEquals(1L, outcomes.stream().filter(ConcurrentRefundOutcome::created).count());
        assertEquals(1L, outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof IdempotencyConflictException)
                .count());
        assertEquals(1L, countRefunds(merchant.getId(), idempotencyKey));

        RefundCreationResult winner = outcomes.stream()
                .map(ConcurrentRefundOutcome::result)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        Payment reloadedFirstPayment = paymentRepository.findById(firstPayment.getId())
                .orElseThrow();
        Payment reloadedSecondPayment = paymentRepository.findById(secondPayment.getId())
                .orElseThrow();
        assertEquals(
                winner.refund().getPayment().getId().equals(firstPayment.getId())
                        ? winner.refund().getAmount()
                        : 0L,
                reloadedFirstPayment.getPendingRefundAmount());
        assertEquals(
                winner.refund().getPayment().getId().equals(secondPayment.getId())
                        ? winner.refund().getAmount()
                        : 0L,
                reloadedSecondPayment.getPendingRefundAmount());
    }

    @Test
    void concurrentDifferentMerchantsCanReuseSameRefundKey() throws Exception {
        Merchant firstMerchant = createMerchant("First Concurrent Shared Refund Key");
        Merchant secondMerchant = createMerchant("Second Concurrent Shared Refund Key");
        Payment firstPayment = createSucceededPayment(firstMerchant);
        Payment secondPayment = createSucceededPayment(secondMerchant);
        String idempotencyKey = "concurrent-shared-refund-key";
        coordinateRefundLookups(Set.of(idempotencyKey), 4);

        List<ConcurrentRefundOutcome> outcomes = runConcurrently(
                () -> refundService.createRefund(
                        firstMerchant,
                        firstPayment.getId(),
                        request(300L),
                        idempotencyKey),
                () -> refundService.createRefund(
                        secondMerchant,
                        secondPayment.getId(),
                        request(400L),
                        idempotencyKey));

        assertTrue(outcomes.stream().allMatch(ConcurrentRefundOutcome::created));
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure() == null));
        assertEquals(1L, countRefunds(firstMerchant.getId(), idempotencyKey));
        assertEquals(1L, countRefunds(secondMerchant.getId(), idempotencyKey));
        assertEquals(
                300L,
                paymentRepository.findById(firstPayment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
        assertEquals(
                400L,
                paymentRepository.findById(secondPayment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
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

    private Payment createSucceededPayment(Merchant merchant) {
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        PaymentCreationResult creation = paymentService.createPayment(
                merchant,
                order.getId(),
                UUID.randomUUID().toString());
        paymentService.simulatePayment(
                merchant,
                creation.payment().getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);
        return paymentRepository.findById(creation.payment().getId()).orElseThrow();
    }

    private CreateRefundRequest request(long amount) {
        return new CreateRefundRequest(amount, RefundReasonCode.CUSTOMER_REQUEST);
    }

    private void coordinateOwnedPaymentChecks(Payment payment, Merchant merchant) {
        CyclicBarrier checksCompletedBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM payment WHERE id = ? AND merchant_id = ?)",
                    Boolean.class,
                    payment.getId(),
                    merchant.getId());
            checksCompletedBarrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(exists);
        })
                .when(paymentRepository)
                .existsByIdAndMerchantId(payment.getId(), merchant.getId());
    }

    private void coordinateRefundLookups(Set<String> idempotencyKeys, int lookupCount) {
        AtomicInteger coordinatedCalls = new AtomicInteger();
        CyclicBarrier queryCompletedBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            UUID merchantId = invocation.getArgument(0);
            String idempotencyKey = invocation.getArgument(1);
            Optional<Refund> result = loadRefund(
                    merchantId,
                    idempotencyKey);
            int callNumber = coordinatedCalls.incrementAndGet();
            if (idempotencyKeys.contains(idempotencyKey) && callNumber <= lookupCount) {
                queryCompletedBarrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        })
                .when(refundRepository)
                .findByMerchantIdAndIdempotencyKey(
                        any(UUID.class),
                        any(String.class));
    }

    private Optional<Refund> loadRefund(
            UUID merchantId,
            String idempotencyKey) {
        List<UUID> refundIds = jdbcTemplate.query(
                """
                SELECT id
                FROM refund
                WHERE merchant_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                merchantId,
                idempotencyKey);
        return refundIds.stream().findFirst().flatMap(refundRepository::findById);
    }

    private List<ConcurrentRefundOutcome> runConcurrently(
            Supplier<RefundCreationResult> firstOperation,
            Supplier<RefundCreationResult> secondOperation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<ConcurrentRefundOutcome> first = executor.submit(
                    () -> runAfterStartSignal(firstOperation, ready, start));
            Future<ConcurrentRefundOutcome> second = executor.submit(
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

    private ConcurrentRefundOutcome runAfterStartSignal(
            Supplier<RefundCreationResult> operation,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new ConcurrentRefundOutcome(operation.get(), null);
        } catch (RuntimeException exception) {
            return new ConcurrentRefundOutcome(null, exception);
        }
    }

    private long countRefunds(UUID merchantId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund WHERE merchant_id = ? AND idempotency_key = ?",
                Long.class,
                merchantId,
                idempotencyKey);
    }

    private long countRefundsForPayment(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund WHERE payment_id = ?",
                Long.class,
                paymentId);
    }

    private long countPendingRefundsForPayment(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund WHERE payment_id = ? AND status = 'PENDING'",
                Long.class,
                paymentId);
    }

    private record ConcurrentRefundOutcome(
            RefundCreationResult result,
            RuntimeException failure) {

        boolean created() {
            return result != null && !result.replayed();
        }

        boolean replayed() {
            return result != null && result.replayed();
        }
    }
}
