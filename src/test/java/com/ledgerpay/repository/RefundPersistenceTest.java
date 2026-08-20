package com.ledgerpay.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RefundPersistenceTest {

    @Autowired
    private RefundRepository refundRepository;

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
    void savesAndReloadsPendingRefund() {
        Merchant merchant = persistMerchant("Refund Persistence Merchant");
        Payment payment = persistSucceededPayment(merchant, "refund-payment-key");
        Refund refund = new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "refund-save-key");

        Refund savedRefund = refundRepository.saveAndFlush(refund);
        UUID refundId = savedRefund.getId();
        entityManager.clear();

        Refund reloadedRefund = refundRepository.findById(refundId).orElseThrow();

        assertNotNull(reloadedRefund);
        assertEquals(payment.getId(), reloadedRefund.getPayment().getId());
        assertEquals(merchant.getId(), reloadedRefund.getMerchant().getId());
        assertEquals(300L, reloadedRefund.getAmount());
        assertEquals(OrderCurrency.EUR, reloadedRefund.getCurrency());
        assertEquals(RefundStatus.PENDING, reloadedRefund.getStatus());
        assertEquals(RefundReasonCode.CUSTOMER_REQUEST, reloadedRefund.getReasonCode());
        assertNull(reloadedRefund.getFailureCode());
        assertEquals("refund-save-key", reloadedRefund.getIdempotencyKey());
        assertNotNull(reloadedRefund.getCreatedAt());
        assertNotNull(reloadedRefund.getUpdatedAt());
    }

    @Test
    void merchantScopedLookupReturnsOnlyOwnedRefund() {
        Merchant owner = persistMerchant("Refund Owner");
        Merchant differentMerchant = persistMerchant("Different Lookup Merchant");
        Payment payment = persistSucceededPayment(owner, "scoped-refund-payment-key");
        Refund refund = refundRepository.saveAndFlush(new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "scoped-refund-key"));

        assertEquals(
                refund.getId(),
                refundRepository.findByIdAndMerchantId(refund.getId(), owner.getId())
                        .orElseThrow()
                        .getId());
        assertTrue(refundRepository.findByIdAndMerchantId(
                refund.getId(),
                differentMerchant.getId()).isEmpty());
    }

    @Test
    void paymentHistoryReturnsOnlyThatPaymentsRefundsNewestFirst() {
        Merchant merchant = persistMerchant("Refund History Merchant");
        Payment payment = persistSucceededPayment(merchant, "history-payment-key");
        Payment otherPayment = persistSucceededPayment(merchant, "other-history-payment-key");
        Refund olderRefund = refundRepository.saveAndFlush(new Refund(
                payment,
                100L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "older-refund-key"));
        Refund newerRefund = refundRepository.saveAndFlush(new Refund(
                payment,
                200L,
                RefundReasonCode.OTHER,
                "newer-refund-key"));
        Refund otherPaymentRefund = refundRepository.saveAndFlush(new Refund(
                otherPayment,
                300L,
                RefundReasonCode.PRODUCT_NOT_RECEIVED,
                "other-payment-refund-key"));
        Instant olderCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant newerCreatedAt = olderCreatedAt.plusSeconds(1);
        updateCreatedAt(olderRefund.getId(), olderCreatedAt);
        updateCreatedAt(newerRefund.getId(), newerCreatedAt);
        updateCreatedAt(otherPaymentRefund.getId(), newerCreatedAt.plusSeconds(1));
        entityManager.clear();

        List<Refund> refunds = refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId());
        List<UUID> refundIds = refunds.stream()
                .map(Refund::getId)
                .toList();

        assertEquals(List.of(newerRefund.getId(), olderRefund.getId()), refundIds);
        assertTrue(refunds.get(0).getCreatedAt().isAfter(refunds.get(1).getCreatedAt()));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveAmounts")
    void rejectsNonPositiveAmount(long amount) {
        Merchant merchant = persistMerchant("Invalid Amount Merchant");
        Payment payment = persistSucceededPayment(merchant, UUID.randomUUID().toString());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRefund(
                        payment.getId(),
                        merchant.getId(),
                        amount,
                        "PENDING",
                        "CUSTOMER_REQUEST",
                        null,
                        UUID.randomUUID().toString()));
    }

    @Test
    void rejectsRefundWhoseMerchantDoesNotOwnPayment() {
        Merchant paymentOwner = persistMerchant("Payment Owner");
        Merchant differentMerchant = persistMerchant("Different Refund Merchant");
        Payment payment = persistSucceededPayment(paymentOwner, "ownership-payment-key");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRefund(
                        payment.getId(),
                        differentMerchant.getId(),
                        300L,
                        "PENDING",
                        "CUSTOMER_REQUEST",
                        null,
                        "ownership-refund-key"));
    }

    @Test
    void rejectsDuplicateIdempotencyKeyWithinMerchant() {
        Merchant merchant = persistMerchant("Refund Idempotency Merchant");
        Payment payment = persistSucceededPayment(merchant, "duplicate-refund-payment-key");
        insertRefund(
                payment.getId(),
                merchant.getId(),
                200L,
                "PENDING",
                "DUPLICATE_CHARGE",
                null,
                "duplicate-refund-key");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRefund(
                        payment.getId(),
                        merchant.getId(),
                        100L,
                        "PENDING",
                        "OTHER",
                        null,
                        "duplicate-refund-key"));
    }

    @Test
    void allowsDifferentMerchantsToReuseIdempotencyKey() {
        Merchant firstMerchant = persistMerchant("First Refund Merchant");
        Merchant secondMerchant = persistMerchant("Second Refund Merchant");
        Payment firstPayment = persistSucceededPayment(firstMerchant, "first-refund-payment-key");
        Payment secondPayment = persistSucceededPayment(secondMerchant, "second-refund-payment-key");
        Refund firstRefund = new Refund(
                firstPayment,
                200L,
                RefundReasonCode.PRODUCT_NOT_RECEIVED,
                "shared-refund-key");
        Refund secondRefund = new Refund(
                secondPayment,
                300L,
                RefundReasonCode.PRODUCT_NOT_RECEIVED,
                "shared-refund-key");

        refundRepository.saveAndFlush(firstRefund);
        refundRepository.saveAndFlush(secondRefund);

        assertNotNull(firstRefund.getId());
        assertNotNull(secondRefund.getId());
        assertEquals(
                firstRefund.getId(),
                refundRepository.findByMerchantIdAndIdempotencyKey(
                                firstMerchant.getId(),
                                "shared-refund-key")
                        .orElseThrow()
                        .getId());
        assertEquals(
                secondRefund.getId(),
                refundRepository.findByMerchantIdAndIdempotencyKey(
                                secondMerchant.getId(),
                                "shared-refund-key")
                        .orElseThrow()
                        .getId());
    }

    @ParameterizedTest
    @MethodSource("invalidFailureCodeStates")
    void rejectsInvalidFailureCodeState(String status, String failureCode) {
        Merchant merchant = persistMerchant("Refund Lifecycle Merchant");
        Payment payment = persistSucceededPayment(merchant, UUID.randomUUID().toString());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRefund(
                        payment.getId(),
                        merchant.getId(),
                        300L,
                        status,
                        "OTHER",
                        failureCode,
                        UUID.randomUUID().toString()));
    }

    private static Stream<Long> nonPositiveAmounts() {
        return Stream.of(0L, -1L);
    }

    private static Stream<Arguments> invalidFailureCodeStates() {
        return Stream.of(
                Arguments.of("PENDING", "REFUND_PROCESSING_ERROR"),
                Arguments.of("SUCCEEDED", "REFUND_PROCESSING_ERROR"),
                Arguments.of("FAILED", null),
                Arguments.of("FAILED", "PAYMENT_NOT_REFUNDABLE"));
    }

    private Merchant persistMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        return merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
    }

    private Payment persistSucceededPayment(Merchant merchant, String idempotencyKey) {
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment payment = new Payment(order, idempotencyKey);
        payment.markSucceeded(Instant.now());
        return paymentRepository.saveAndFlush(payment);
    }

    private void updateCreatedAt(UUID refundId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE refund SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                refundId);
    }

    private void insertRefund(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String status,
            String reasonCode,
            String failureCode,
            String idempotencyKey) {
        jdbcTemplate.update(
                """
                INSERT INTO refund (
                    id,
                    payment_id,
                    merchant_id,
                    amount,
                    currency,
                    status,
                    reason_code,
                    failure_code,
                    idempotency_key
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                paymentId,
                merchantId,
                amount,
                "EUR",
                status,
                reasonCode,
                failureCode,
                idempotencyKey);
    }
}
