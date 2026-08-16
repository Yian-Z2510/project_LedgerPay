package com.ledgerpay.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentStatus;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReloadsPayment() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Persistence Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment payment = new Payment(order, "payment-save-key");

        Payment savedPayment = paymentRepository.saveAndFlush(payment);
        UUID paymentId = savedPayment.getId();
        entityManager.clear();

        Payment reloadedPayment = paymentRepository.findById(paymentId).orElseThrow();

        assertNotNull(reloadedPayment.getId());
        assertEquals(merchant.getId(), reloadedPayment.getMerchant().getId());
        assertEquals(order.getId(), reloadedPayment.getOrder().getId());
        assertEquals(1000L, reloadedPayment.getAmount());
        assertEquals(OrderCurrency.EUR, reloadedPayment.getCurrency());
        assertEquals(PaymentStatus.PENDING, reloadedPayment.getStatus());
        assertEquals(0L, reloadedPayment.getRefundedAmount());
        assertEquals(0L, reloadedPayment.getPendingRefundAmount());
        assertNull(reloadedPayment.getFailureCode());
        assertNull(reloadedPayment.getCompletedAt());
        assertEquals("payment-save-key", reloadedPayment.getIdempotencyKey());
        assertNotNull(reloadedPayment.getCreatedAt());
        assertNotNull(reloadedPayment.getUpdatedAt());
    }

    @Test
    void merchantScopedLookupsReturnOnlyOwnedPayment() {
        Merchant owner = merchantRepository.saveAndFlush(createMerchant("Owning Merchant"));
        Merchant differentMerchant = merchantRepository.saveAndFlush(createMerchant("Different Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(owner, 1000L));
        Payment payment = paymentRepository.saveAndFlush(new Payment(order, "merchant-scoped-key"));

        Optional<Payment> ownerIdResult = paymentRepository.findByIdAndMerchantId(
                payment.getId(),
                owner.getId());
        Optional<Payment> differentMerchantIdResult = paymentRepository.findByIdAndMerchantId(
                payment.getId(),
                differentMerchant.getId());
        Optional<Payment> ownerKeyResult = paymentRepository.findByMerchantIdAndIdempotencyKey(
                owner.getId(),
                "merchant-scoped-key");
        Optional<Payment> differentMerchantKeyResult = paymentRepository.findByMerchantIdAndIdempotencyKey(
                differentMerchant.getId(),
                "merchant-scoped-key");

        assertEquals(payment.getId(), ownerIdResult.orElseThrow().getId());
        assertTrue(differentMerchantIdResult.isEmpty());
        assertEquals(payment.getId(), ownerKeyResult.orElseThrow().getId());
        assertTrue(differentMerchantKeyResult.isEmpty());
    }

    @Test
    void findByOrderIdOrderByCreatedAtDescReturnsAllAttemptsNewestFirst() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("History Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment olderPayment = paymentRepository.saveAndFlush(new Payment(order, "older-payment-key"));
        olderPayment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        paymentRepository.saveAndFlush(olderPayment);
        Payment newerPayment = paymentRepository.saveAndFlush(new Payment(order, "newer-payment-key"));

        Instant olderCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant newerCreatedAt = olderCreatedAt.plusSeconds(1);
        updateCreatedAt(olderPayment.getId(), olderCreatedAt);
        updateCreatedAt(newerPayment.getId(), newerCreatedAt);
        entityManager.clear();

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());
        List<UUID> paymentIds = payments.stream()
                .map(Payment::getId)
                .toList();

        assertEquals(List.of(newerPayment.getId(), olderPayment.getId()), paymentIds);
        assertTrue(payments.get(0).getCreatedAt().isAfter(payments.get(1).getCreatedAt()));
    }

    @Test
    void existenceMethodsReflectPaymentAndStatus() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Existence Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));

        assertFalse(paymentRepository.existsByOrderId(order.getId()));

        Payment payment = paymentRepository.saveAndFlush(new Payment(order, "existence-key"));

        assertTrue(paymentRepository.existsByOrderId(order.getId()));
        assertTrue(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING));
        assertFalse(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCEEDED));

        payment.markSucceeded(Instant.now());
        paymentRepository.saveAndFlush(payment);

        assertFalse(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING));
        assertTrue(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCEEDED));
    }

    @Test
    void rejectsDuplicateIdempotencyKeyWithinMerchant() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Idempotency Merchant"));
        MerchantOrder firstOrder = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        MerchantOrder secondOrder = orderRepository.saveAndFlush(new MerchantOrder(merchant, 2000L));
        paymentRepository.saveAndFlush(new Payment(firstOrder, "duplicate-key"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentRepository.saveAndFlush(new Payment(secondOrder, "duplicate-key")));
    }

    @Test
    void allowsDifferentMerchantsToReuseIdempotencyKey() {
        Merchant firstMerchant = merchantRepository.saveAndFlush(createMerchant("First Idempotency Merchant"));
        Merchant secondMerchant = merchantRepository.saveAndFlush(createMerchant("Second Idempotency Merchant"));
        MerchantOrder firstOrder = orderRepository.saveAndFlush(new MerchantOrder(firstMerchant, 1000L));
        MerchantOrder secondOrder = orderRepository.saveAndFlush(new MerchantOrder(secondMerchant, 1000L));

        Payment firstPayment = paymentRepository.saveAndFlush(new Payment(firstOrder, "shared-key"));
        Payment secondPayment = paymentRepository.saveAndFlush(new Payment(secondOrder, "shared-key"));

        assertNotNull(firstPayment.getId());
        assertNotNull(secondPayment.getId());
    }

    @Test
    void rejectsPaymentWhoseMerchantDoesNotOwnOrder() {
        Merchant orderOwner = merchantRepository.saveAndFlush(createMerchant("Order Owner"));
        Merchant differentMerchant = merchantRepository.saveAndFlush(createMerchant("Different Payment Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(orderOwner, 1000L));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        order.getId(),
                        differentMerchant.getId(),
                        "ownership-mismatch-key",
                        "PENDING",
                        0L,
                        0L,
                        null,
                        null));
    }

    @Test
    void rejectsSecondPendingPaymentForOrder() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Pending Payment Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        paymentRepository.saveAndFlush(new Payment(order, "first-pending-key"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentRepository.saveAndFlush(new Payment(order, "second-pending-key")));
    }

    @Test
    void rejectsSecondSucceededPaymentForOrder() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Succeeded Payment Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment failedPayment = new Payment(order, "historical-failed-key");
        failedPayment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        paymentRepository.saveAndFlush(failedPayment);
        Payment firstSucceededPayment = new Payment(order, "first-succeeded-key");
        firstSucceededPayment.markSucceeded(Instant.now());
        paymentRepository.saveAndFlush(firstSucceededPayment);
        Payment secondSucceededPayment = new Payment(order, "second-succeeded-key");
        secondSucceededPayment.markSucceeded(Instant.now());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentRepository.saveAndFlush(secondSucceededPayment));
    }

    @Test
    void allowsMultipleFailedPaymentsForOrder() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Failed Payment Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment firstPayment = new Payment(order, "first-failed-key");
        firstPayment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        Payment secondPayment = new Payment(order, "second-failed-key");
        secondPayment.markFailed(PaymentFailureCode.PROCESSING_ERROR, Instant.now());

        paymentRepository.saveAndFlush(firstPayment);
        paymentRepository.saveAndFlush(secondPayment);

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());

        assertEquals(2, payments.size());
        assertTrue(payments.stream().allMatch(payment -> payment.getStatus() == PaymentStatus.FAILED));
    }

    @ParameterizedTest
    @MethodSource("invalidLifecycleStates")
    void rejectsInvalidLifecycleState(
            String status,
            String failureCode,
            Instant completedAt) {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Lifecycle Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        order.getId(),
                        merchant.getId(),
                        UUID.randomUUID().toString(),
                        status,
                        0L,
                        0L,
                        failureCode,
                        completedAt));
    }

    @ParameterizedTest
    @MethodSource("invalidRefundSummaries")
    void rejectsInvalidRefundSummary(long refundedAmount, long pendingRefundAmount) {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Refund Summary Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        order.getId(),
                        merchant.getId(),
                        UUID.randomUUID().toString(),
                        "PENDING",
                        refundedAmount,
                        pendingRefundAmount,
                        null,
                        null));
    }

    private static Stream<Arguments> invalidLifecycleStates() {
        Instant completedAt = Instant.parse("2026-01-01T00:00:00Z");
        return Stream.of(
                Arguments.of("PENDING", null, completedAt),
                Arguments.of("PENDING", "PAYMENT_DECLINED", null),
                Arguments.of("SUCCEEDED", null, null),
                Arguments.of("SUCCEEDED", "PAYMENT_DECLINED", completedAt),
                Arguments.of("FAILED", "PAYMENT_DECLINED", null),
                Arguments.of("FAILED", null, completedAt));
    }

    private static Stream<Arguments> invalidRefundSummaries() {
        return Stream.of(
                Arguments.of(-1L, 0L),
                Arguments.of(0L, -1L),
                Arguments.of(800L, 300L));
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        return new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2));
    }

    private void updateCreatedAt(UUID paymentId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE payment SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                paymentId);
    }

    private void insertPayment(
            UUID orderId,
            UUID merchantId,
            String idempotencyKey,
            String status,
            long refundedAmount,
            long pendingRefundAmount,
            String failureCode,
            Instant completedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO payment (
                    id,
                    merchant_order_id,
                    merchant_id,
                    amount,
                    currency,
                    status,
                    idempotency_key,
                    refunded_amount,
                    pending_refund_amount,
                    failure_code,
                    completed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                orderId,
                merchantId,
                1000L,
                "EUR",
                status,
                idempotencyKey,
                refundedAmount,
                pendingRefundAmount,
                failureCode,
                completedAt == null ? null : Timestamp.from(completedAt));
    }
}
