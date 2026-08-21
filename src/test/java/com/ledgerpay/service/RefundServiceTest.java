package com.ledgerpay.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.InsufficientRefundableAmountException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.PaymentNotRefundableException;
import com.ledgerpay.exception.RefundNotFoundException;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final String IDEMPOTENCY_KEY = "refund-key";
    private static final CreateRefundRequest REQUEST = new CreateRefundRequest(
            300L,
            RefundReasonCode.CUSTOMER_REQUEST);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RefundRepository refundRepository;

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(paymentRepository, refundRepository);
    }

    @Test
    void getRefundReturnsOwnedRefundUsingMerchantScope() {
        Merchant merchant = persistedMerchant("get-owned@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        Refund refund = persistedRefund(new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                IDEMPOTENCY_KEY));
        when(refundRepository.findByIdAndMerchantId(refund.getId(), merchant.getId()))
                .thenReturn(Optional.of(refund));

        Refund result = refundService.getRefund(merchant, refund.getId());

        assertSame(refund, result);
        verify(refundRepository).findByIdAndMerchantId(refund.getId(), merchant.getId());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void getRefundMapsMissingRefundToNotFound() {
        assertRefundNotFound(persistedMerchant("get-missing@example.com"), UUID.randomUUID());
    }

    @Test
    void getRefundHidesForeignRefundAsNotFound() {
        assertRefundNotFound(persistedMerchant("get-foreign@example.com"), UUID.randomUUID());
    }

    @Test
    void listRefundsForPaymentReturnsRepositoryCreatedAtDescendingOrder() {
        Merchant merchant = persistedMerchant("history-owned@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        Refund newerRefund = persistedRefund(new Refund(
                payment,
                200L,
                RefundReasonCode.OTHER,
                "newer-key"));
        Refund olderRefund = persistedRefund(new Refund(
                payment,
                100L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "older-key"));
        when(paymentRepository.findByIdAndMerchantId(payment.getId(), merchant.getId()))
                .thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId()))
                .thenReturn(List.of(newerRefund, olderRefund));

        List<Refund> result = refundService.listRefundsForPayment(
                merchant,
                payment.getId());

        assertEquals(List.of(newerRefund, olderRefund), result);
        InOrder inOrder = inOrder(paymentRepository, refundRepository);
        inOrder.verify(paymentRepository).findByIdAndMerchantId(
                payment.getId(), merchant.getId());
        inOrder.verify(refundRepository).findByPaymentIdOrderByCreatedAtDesc(payment.getId());
    }

    @Test
    void listRefundsForOwnedPaymentReturnsEmptyHistory() {
        Merchant merchant = persistedMerchant("history-empty@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        when(paymentRepository.findByIdAndMerchantId(payment.getId(), merchant.getId()))
                .thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId()))
                .thenReturn(List.of());

        List<Refund> result = refundService.listRefundsForPayment(
                merchant,
                payment.getId());

        assertTrue(result.isEmpty());
    }

    @Test
    void listRefundsMapsMissingPaymentToNotFoundWithoutHistoryLookup() {
        assertPaymentHistoryNotFound(
                persistedMerchant("history-missing@example.com"),
                UUID.randomUUID());
    }

    @Test
    void listRefundsHidesForeignPaymentAsNotFoundWithoutHistoryLookup() {
        assertPaymentHistoryNotFound(
                persistedMerchant("history-foreign@example.com"),
                UUID.randomUUID());
    }

    @Test
    void createRefundPersistsPendingRefundAndReservesCapacityInRequiredOrder() {
        Merchant merchant = persistedMerchant("owner@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        stubNewRequest(merchant, payment);
        when(refundRepository.saveAndFlush(any(Refund.class)))
                .thenAnswer(invocation -> persistedRefund(invocation.getArgument(0)));
        when(paymentRepository.saveAndFlush(payment)).thenReturn(payment);

        RefundCreationResult result = refundService.createRefund(
                merchant,
                payment.getId(),
                REQUEST,
                IDEMPOTENCY_KEY);

        assertFalse(result.replayed());
        assertEquals(RefundStatus.PENDING, result.refund().getStatus());
        assertEquals(payment.getId(), result.refund().getPayment().getId());
        assertEquals(merchant.getId(), result.refund().getMerchant().getId());
        assertEquals(300L, result.refund().getAmount());
        assertEquals(RefundReasonCode.CUSTOMER_REQUEST, result.refund().getReasonCode());
        assertEquals(IDEMPOTENCY_KEY, result.refund().getIdempotencyKey());
        assertEquals(300L, payment.getPendingRefundAmount());

        InOrder inOrder = inOrder(paymentRepository, refundRepository);
        inOrder.verify(paymentRepository).existsByIdAndMerchantId(
                payment.getId(), merchant.getId());
        inOrder.verify(refundRepository).findByMerchantIdAndIdempotencyKey(
                merchant.getId(), IDEMPOTENCY_KEY);
        inOrder.verify(paymentRepository).findForUpdateByIdAndMerchantId(
                payment.getId(), merchant.getId());
        inOrder.verify(refundRepository).saveAndFlush(any(Refund.class));
        inOrder.verify(paymentRepository).saveAndFlush(payment);
    }

    @Test
    void pendingPaymentIsNotRefundableWithoutWritesOrSummaryChange() {
        assertPaymentNotRefundable(pendingPayment(persistedMerchant("pending@example.com")));
    }

    @Test
    void failedPaymentIsNotRefundableWithoutWritesOrSummaryChange() {
        Merchant merchant = persistedMerchant("failed@example.com");
        Payment payment = pendingPayment(merchant);
        payment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());

        assertPaymentNotRefundable(payment);
    }

    @Test
    void insufficientCapacityRejectsWithoutWritesOrSummaryChange() {
        Merchant merchant = persistedMerchant("insufficient@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        ReflectionTestUtils.setField(payment, "refundedAmount", 500L);
        ReflectionTestUtils.setField(payment, "pendingRefundAmount", 300L);
        stubNewRequest(merchant, payment);

        assertThrows(
                InsufficientRefundableAmountException.class,
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        REQUEST,
                        IDEMPOTENCY_KEY));

        assertEquals(500L, payment.getRefundedAmount());
        assertEquals(300L, payment.getPendingRefundAmount());
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void exactHistoricalRequestReplaysBeforeLockOrMutableValidation() {
        Merchant merchant = persistedMerchant("replay@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        Refund historicalRefund = persistedRefund(new Refund(
                payment,
                REQUEST.amount(),
                REQUEST.reasonCode(),
                IDEMPOTENCY_KEY));
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);
        ReflectionTestUtils.setField(payment, "pendingRefundAmount", 1000L);
        when(paymentRepository.existsByIdAndMerchantId(payment.getId(), merchant.getId()))
                .thenReturn(true);
        when(refundRepository.findByMerchantIdAndIdempotencyKey(
                        merchant.getId(), IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(historicalRefund));

        RefundCreationResult result = refundService.createRefund(
                merchant,
                payment.getId(),
                REQUEST,
                IDEMPOTENCY_KEY);

        assertTrue(result.replayed());
        assertSame(historicalRefund, result.refund());
        verify(paymentRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
    }

    @Test
    void sameKeyWithDifferentPaymentConflictsBeforeLock() {
        Merchant merchant = persistedMerchant("payment-conflict@example.com");
        Payment requestedPayment = succeededPayment(merchant, 1000L);
        Payment historicalPayment = succeededPayment(merchant, 1000L);
        Refund historicalRefund = persistedRefund(new Refund(
                historicalPayment,
                REQUEST.amount(),
                REQUEST.reasonCode(),
                IDEMPOTENCY_KEY));
        stubHistoricalRequest(merchant, requestedPayment, historicalRefund);

        assertIdempotencyConflict(merchant, requestedPayment, REQUEST);
    }

    @Test
    void sameKeyWithDifferentAmountConflictsBeforeLock() {
        Merchant merchant = persistedMerchant("amount-conflict@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        Refund historicalRefund = persistedRefund(new Refund(
                payment,
                200L,
                REQUEST.reasonCode(),
                IDEMPOTENCY_KEY));
        stubHistoricalRequest(merchant, payment, historicalRefund);

        assertIdempotencyConflict(merchant, payment, REQUEST);
    }

    @Test
    void sameKeyWithDifferentReasonCodeConflictsBeforeLock() {
        Merchant merchant = persistedMerchant("reason-conflict@example.com");
        Payment payment = succeededPayment(merchant, 1000L);
        Refund historicalRefund = persistedRefund(new Refund(
                payment,
                REQUEST.amount(),
                RefundReasonCode.OTHER,
                IDEMPOTENCY_KEY));
        stubHistoricalRequest(merchant, payment, historicalRefund);

        assertIdempotencyConflict(merchant, payment, REQUEST);
    }

    @Test
    void foreignPaymentIsNotFoundBeforeIdempotencyLookup() {
        Merchant merchant = persistedMerchant("foreign@example.com");
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.existsByIdAndMerchantId(paymentId, merchant.getId()))
                .thenReturn(false);

        assertThrows(
                PaymentNotFoundException.class,
                () -> refundService.createRefund(
                        merchant,
                        paymentId,
                        REQUEST,
                        IDEMPOTENCY_KEY));

        verifyNoInteractions(refundRepository);
        verify(paymentRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
    }

    @Test
    void createRefundOwnsTransactionalBoundary() throws Exception {
        assertNotNull(RefundService.class
                .getMethod(
                        "createRefund",
                        Merchant.class,
                        UUID.class,
                        CreateRefundRequest.class,
                        String.class)
                .getAnnotation(Transactional.class));
    }

    private void assertPaymentNotRefundable(Payment payment) {
        Merchant merchant = payment.getMerchant();
        stubNewRequest(merchant, payment);

        assertThrows(
                PaymentNotRefundableException.class,
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        REQUEST,
                        IDEMPOTENCY_KEY));

        assertEquals(0L, payment.getPendingRefundAmount());
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    private void assertRefundNotFound(Merchant merchant, UUID refundId) {
        when(refundRepository.findByIdAndMerchantId(refundId, merchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                RefundNotFoundException.class,
                () -> refundService.getRefund(merchant, refundId));

        verifyNoInteractions(paymentRepository);
    }

    private void assertPaymentHistoryNotFound(Merchant merchant, UUID paymentId) {
        when(paymentRepository.findByIdAndMerchantId(paymentId, merchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                PaymentNotFoundException.class,
                () -> refundService.listRefundsForPayment(merchant, paymentId));

        verify(refundRepository, never()).findByPaymentIdOrderByCreatedAtDesc(any());
    }

    private void assertIdempotencyConflict(
            Merchant merchant,
            Payment requestedPayment,
            CreateRefundRequest request) {
        assertThrows(
                IdempotencyConflictException.class,
                () -> refundService.createRefund(
                        merchant,
                        requestedPayment.getId(),
                        request,
                        IDEMPOTENCY_KEY));

        verify(paymentRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    private void stubNewRequest(Merchant merchant, Payment payment) {
        when(paymentRepository.existsByIdAndMerchantId(payment.getId(), merchant.getId()))
                .thenReturn(true);
        when(refundRepository.findByMerchantIdAndIdempotencyKey(
                        merchant.getId(), IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.findForUpdateByIdAndMerchantId(
                        payment.getId(), merchant.getId()))
                .thenReturn(Optional.of(payment));
    }

    private void stubHistoricalRequest(
            Merchant merchant,
            Payment requestedPayment,
            Refund historicalRefund) {
        when(paymentRepository.existsByIdAndMerchantId(
                        requestedPayment.getId(), merchant.getId()))
                .thenReturn(true);
        when(refundRepository.findByMerchantIdAndIdempotencyKey(
                        merchant.getId(), IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(historicalRefund));
    }

    private Merchant persistedMerchant(String email) {
        Merchant merchant = new Merchant("Refund Service Merchant", email, "a".repeat(64));
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private Payment pendingPayment(Merchant merchant) {
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        Payment payment = new Payment(order, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }

    private Payment succeededPayment(Merchant merchant, long amount) {
        MerchantOrder order = new MerchantOrder(merchant, amount);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        Payment payment = new Payment(order, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.markSucceeded(Instant.now());
        return payment;
    }

    private Refund persistedRefund(Refund refund) {
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        return refund;
    }
}
