package com.ledgerpay.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.dto.UpdateOrderRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class OrderMutationPostgresAcceptanceTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    @Autowired
    private MerchantRepository merchantRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID merchantId;

    @AfterEach
    void removePersistedTestData() {
        if (merchantId != null) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void updateOrderAndPaymentCreationSerializeOnSameOrderRowLock() throws Exception {
        Merchant merchant = createMerchant("Update Lock Merchant");
        MerchantOrder order = createOrder(merchant);
        LockGate lockGate = pauseFirstOrderLock(order, merchant);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OrderResponse> update = executor.submit(() -> orderService.updateOrder(
                    merchant,
                    order.getId(),
                    new UpdateOrderRequest(1200L)));
            assertTrue(lockGate.acquired().await(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS));

            Future<PaymentCreationResult> payment = executor.submit(() ->
                    paymentService.createPayment(
                            merchant,
                            order.getId(),
                            "update-lock-key"));

            assertThrows(
                    TimeoutException.class,
                    () -> payment.get(250, TimeUnit.MILLISECONDS));
            lockGate.release().countDown();

            assertEquals(
                    1200L,
                    update.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS).amount());
            PaymentCreationResult paymentResult = payment.get(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            assertEquals(1200L, paymentResult.payment().getAmount());
            assertEquals(1L, countPayments(order.getId()));
        } finally {
            lockGate.release().countDown();
            shutdown(executor);
        }
    }

    @Test
    void cancelOrderAndPaymentCreationSerializeOnSameOrderRowLock() throws Exception {
        Merchant merchant = createMerchant("Cancel Lock Merchant");
        MerchantOrder order = createOrder(merchant);
        LockGate lockGate = pauseFirstOrderLock(order, merchant);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OrderResponse> cancellation = executor.submit(() ->
                    orderService.cancelOrder(merchant, order.getId()));
            assertTrue(lockGate.acquired().await(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS));

            Future<PaymentCreationResult> payment = executor.submit(() ->
                    paymentService.createPayment(
                            merchant,
                            order.getId(),
                            "cancel-lock-key"));

            assertThrows(
                    TimeoutException.class,
                    () -> payment.get(250, TimeUnit.MILLISECONDS));
            lockGate.release().countDown();

            OrderResponse response = cancellation.get(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            assertEquals(OrderStatus.CANCELLED, response.status());
            ExecutionException paymentFailure = assertThrows(
                    ExecutionException.class,
                    () -> payment.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(OrderInvalidStateException.class, paymentFailure.getCause());
            assertEquals(0L, countPayments(order.getId()));
            assertEquals("CANCELLED", loadOrderStatus(order.getId()));
        } finally {
            lockGate.release().countDown();
            shutdown(executor);
        }
    }

    @Test
    void historicalFailedPaymentStillBlocksUpdateWhenOrderIsCreated() {
        Merchant merchant = createMerchant("Failed Payment Update Merchant");
        MerchantOrder order = createOrder(merchant);
        PaymentCreationResult payment = paymentService.createPayment(
                merchant,
                order.getId(),
                "historical-failed-update-key");
        paymentService.simulatePayment(
                merchant,
                payment.payment().getId(),
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PAYMENT_DECLINED);
        jdbcTemplate.update(
                "UPDATE merchant_order SET status = 'CREATED' WHERE id = ?",
                order.getId());

        assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.updateOrder(
                        merchant,
                        order.getId(),
                        new UpdateOrderRequest(1200L)));

        assertEquals(1000L, loadOrderAmount(order.getId()));
    }

    @Test
    void paymentPendingOrderWithOnlyFailedHistoryCanBeCancelled() {
        Merchant merchant = createMerchant("Failed Payment Cancel Merchant");
        MerchantOrder order = createOrder(merchant);
        PaymentCreationResult payment = paymentService.createPayment(
                merchant,
                order.getId(),
                "historical-failed-cancel-key");
        paymentService.simulatePayment(
                merchant,
                payment.payment().getId(),
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PROCESSING_ERROR);

        OrderResponse response = orderService.cancelOrder(merchant, order.getId());

        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals("CANCELLED", loadOrderStatus(order.getId()));
    }

    private LockGate pauseFirstOrderLock(MerchantOrder order, Merchant merchant) {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicBoolean pauseFirstCall = new AtomicBoolean(true);

        doAnswer(invocation -> {
            Optional<MerchantOrder> result = lockOwnedOrderWithSql(
                    invocation.getArgument(0),
                    invocation.getArgument(1));
            if (pauseFirstCall.compareAndSet(true, false)) {
                lockAcquired.countDown();
                assertTrue(releaseLock.await(
                        CONCURRENCY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS));
            }
            return result;
        })
                .when(orderRepository)
                .findForUpdateByIdAndMerchantId(eq(order.getId()), eq(merchant.getId()));

        return new LockGate(lockAcquired, releaseLock);
    }

    private Optional<MerchantOrder> lockOwnedOrderWithSql(UUID orderId, UUID ownerMerchantId) {
        List<UUID> matchedOrderIds = jdbcTemplate.query(
                """
                SELECT id
                FROM merchant_order
                WHERE id = ? AND merchant_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                orderId,
                ownerMerchantId);
        if (matchedOrderIds.isEmpty()) {
            return Optional.empty();
        }
        return orderRepository.findByIdAndMerchantId(orderId, ownerMerchantId);
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
        merchantId = merchant.getId();
        return merchant;
    }

    private MerchantOrder createOrder(Merchant merchant) {
        return orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
    }

    private long countPayments(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE merchant_order_id = ?",
                Long.class,
                orderId);
    }

    private String loadOrderStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_order WHERE id = ?",
                String.class,
                orderId);
    }

    private long loadOrderAmount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM merchant_order WHERE id = ?",
                Long.class,
                orderId);
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(
                CONCURRENCY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS));
    }

    private record LockGate(
            CountDownLatch acquired,
            CountDownLatch release) {
    }
}
