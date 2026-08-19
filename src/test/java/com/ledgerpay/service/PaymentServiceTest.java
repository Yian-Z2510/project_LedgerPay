package com.ledgerpay.service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.OrderAlreadyPaidException;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.exception.PaymentInvalidStateException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        paymentService = new PaymentService(
                paymentRepository,
                orderRepository,
                webhookEventRepository,
                new ObjectMapper(),
                transactionManager);
    }

    @Test
    void getPaymentReturnsOwnedPaymentUsingMerchantScope() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment payment = new Payment(order, "owned-payment-key");
        UUID paymentId = UUID.randomUUID();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        when(paymentRepository.findByIdAndMerchantId(paymentId, authenticatedMerchant.getId()))
                .thenReturn(Optional.of(payment));

        Payment result = paymentService.getPayment(authenticatedMerchant, paymentId);

        assertSame(payment, result);
        verify(paymentRepository).findByIdAndMerchantId(
                paymentId,
                authenticatedMerchant.getId());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void getPaymentTreatsMissingOrCrossMerchantPaymentAsNotFound() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndMerchantId(paymentId, authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                PaymentNotFoundException.class,
                () -> paymentService.getPayment(authenticatedMerchant, paymentId));

        verify(paymentRepository).findByIdAndMerchantId(
                paymentId,
                authenticatedMerchant.getId());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void listPaymentsForOrderReturnsOwnedOrderHistoryInRepositoryOrder() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment newerPayment = new Payment(order, "newer-payment-key");
        Payment olderPayment = new Payment(order, "older-payment-key");
        when(orderRepository.findByIdAndMerchantId(order.getId(), authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()))
                .thenReturn(List.of(newerPayment, olderPayment));

        List<Payment> result = paymentService.listPaymentsForOrder(
                authenticatedMerchant,
                order.getId());

        assertEquals(List.of(newerPayment, olderPayment), result);
        InOrder inOrder = inOrder(orderRepository, paymentRepository);
        inOrder.verify(orderRepository).findByIdAndMerchantId(
                order.getId(),
                authenticatedMerchant.getId());
        inOrder.verify(paymentRepository).findByOrderIdOrderByCreatedAtDesc(order.getId());
        verify(orderRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
    }

    @Test
    void listPaymentsForOrderReturnsEmptyListForOwnedOrderWithoutPayments() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        when(orderRepository.findByIdAndMerchantId(order.getId(), authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()))
                .thenReturn(List.of());

        List<Payment> result = paymentService.listPaymentsForOrder(
                authenticatedMerchant,
                order.getId());

        assertTrue(result.isEmpty());
        verify(paymentRepository).findByOrderIdOrderByCreatedAtDesc(order.getId());
        verify(orderRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
    }

    @Test
    void listPaymentsForOrderTreatsMissingOrCrossMerchantOrderAsNotFound() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndMerchantId(orderId, authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> paymentService.listPaymentsForOrder(authenticatedMerchant, orderId));

        verify(orderRepository).findByIdAndMerchantId(orderId, authenticatedMerchant.getId());
        verify(orderRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void createPaymentLocksOwnedCreatedOrderAndPersistsPendingPaymentAndOrderUpdate() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        String idempotencyKey = "payment-order-001";
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                idempotencyKey);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        InOrder inOrder = inOrder(orderRepository, paymentRepository);
        inOrder.verify(orderRepository).findForUpdateByIdAndMerchantId(
                order.getId(),
                authenticatedMerchant.getId());
        inOrder.verify(paymentRepository).existsByOrderIdAndStatus(
                order.getId(),
                PaymentStatus.PENDING);
        inOrder.verify(paymentRepository).existsByOrderIdAndStatus(
                order.getId(),
                PaymentStatus.SUCCEEDED);
        inOrder.verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        inOrder.verify(orderRepository).save(order);

        Payment savedPayment = paymentCaptor.getValue();
        assertSame(savedPayment, result.payment());
        assertFalse(result.replayed());
        assertSame(authenticatedMerchant, savedPayment.getMerchant());
        assertSame(order, savedPayment.getOrder());
        assertEquals(order.getAmount(), savedPayment.getAmount());
        assertEquals(OrderCurrency.EUR, savedPayment.getCurrency());
        assertEquals(PaymentStatus.PENDING, savedPayment.getStatus());
        assertEquals(idempotencyKey, savedPayment.getIdempotencyKey());
        assertEquals(0L, savedPayment.getRefundedAmount());
        assertEquals(0L, savedPayment.getPendingRefundAmount());
        assertNull(savedPayment.getFailureCode());
        assertNull(savedPayment.getCompletedAt());
        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
    }

    @Test
    void createPaymentAllowsNewAttemptForPaymentPendingOrderWithOnlyHistoricalFailedPayment() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment historicalPayment = new Payment(order, "historical-failed-key");
        historicalPayment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(false);
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "retry-after-failure-key");

        assertEquals(PaymentStatus.FAILED, historicalPayment.getStatus());
        assertEquals(PaymentStatus.PENDING, result.payment().getStatus());
        assertEquals("retry-after-failure-key", result.payment().getIdempotencyKey());
        assertFalse(result.replayed());
        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
        verify(paymentRepository).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentReplaysCurrentHistoricalPaymentBeforeOrderLookup() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAID);
        Payment historicalPayment = persistedPayment(order, "historical-key");
        Instant completedAt = Instant.parse("2026-08-19T10:00:00Z");
        historicalPayment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, completedAt);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "historical-key"))
                .thenReturn(Optional.of(historicalPayment));

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "historical-key");

        assertSame(historicalPayment, result.payment());
        assertTrue(result.replayed());
        assertEquals(PaymentStatus.FAILED, result.payment().getStatus());
        assertEquals(PaymentFailureCode.PAYMENT_DECLINED, result.payment().getFailureCode());
        assertEquals(completedAt, result.payment().getCompletedAt());
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(transactionManager);
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
    }

    @Test
    void createPaymentRejectsIdempotencyKeyReusedForDifferentOrderBeforeOrderLookup() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder originalOrder = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment historicalPayment = persistedPayment(originalOrder, "reused-key");
        UUID differentOrderId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "reused-key"))
                .thenReturn(Optional.of(historicalPayment));

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        differentOrderId,
                        "reused-key"));

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(transactionManager);
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void createPaymentReplaysPaymentFoundBySecondLookupAfterOrderLock() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAID);
        Payment historicalPayment = persistedPayment(order, "second-lookup-key");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "second-lookup-key"))
                .thenReturn(Optional.empty(), Optional.of(historicalPayment));
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "second-lookup-key");

        assertSame(historicalPayment, result.payment());
        assertTrue(result.replayed());
        InOrder inOrder = inOrder(paymentRepository, transactionManager, orderRepository);
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "second-lookup-key");
        inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        inOrder.verify(orderRepository).findForUpdateByIdAndMerchantId(
                order.getId(),
                authenticatedMerchant.getId());
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "second-lookup-key");
        inOrder.verify(transactionManager).commit(transactionStatus);
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
    }

    @Test
    void createPaymentRejectsDifferentOrderFoundBySecondLookupAfterOrderLock() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder requestedOrder = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        MerchantOrder originalOrder = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment historicalPayment = persistedPayment(originalOrder, "second-conflict-key");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "second-conflict-key"))
                .thenReturn(Optional.empty(), Optional.of(historicalPayment));
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        requestedOrder.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(requestedOrder));

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        requestedOrder.getId(),
                        "second-conflict-key"));

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
    }

    @Test
    void createPaymentRecoversSameOrderWinnerAfterWriteTransactionRollsBack() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        Payment winningPayment = persistedPayment(order, "race-key");
        DataIntegrityViolationException integrityException =
                new DataIntegrityViolationException("duplicate idempotency key");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "race-key"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winningPayment));
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(integrityException);

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "race-key");

        assertSame(winningPayment, result.payment());
        assertTrue(result.replayed());
        InOrder inOrder = inOrder(paymentRepository, transactionManager, orderRepository);
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "race-key");
        inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        inOrder.verify(orderRepository).findForUpdateByIdAndMerchantId(
                order.getId(),
                authenticatedMerchant.getId());
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "race-key");
        inOrder.verify(paymentRepository).saveAndFlush(any(Payment.class));
        inOrder.verify(transactionManager).rollback(transactionStatus);
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "race-key");
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void createPaymentMapsDifferentOrderWinnerAfterRollbackToIdempotencyConflict() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder requestedOrder = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        MerchantOrder winningOrder = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment winningPayment = persistedPayment(winningOrder, "cross-order-race-key");
        DataIntegrityViolationException integrityException =
                new DataIntegrityViolationException("duplicate idempotency key");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "cross-order-race-key"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winningPayment));
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        requestedOrder.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(requestedOrder));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(integrityException);

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        requestedOrder.getId(),
                        "cross-order-race-key"));

        InOrder inOrder = inOrder(transactionManager, paymentRepository);
        inOrder.verify(transactionManager).rollback(transactionStatus);
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "cross-order-race-key");
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void createPaymentRethrowsUnexpectedIntegrityViolationWhenNoWinnerExists() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        DataIntegrityViolationException integrityException =
                new DataIntegrityViolationException("unrelated database constraint");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        "unexpected-integrity-key"))
                .thenReturn(Optional.empty());
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(integrityException);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "unexpected-integrity-key"));

        assertSame(integrityException, thrown);
        InOrder inOrder = inOrder(transactionManager, paymentRepository);
        inOrder.verify(transactionManager).rollback(transactionStatus);
        inOrder.verify(paymentRepository).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "unexpected-integrity-key");
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void createPaymentScopesIdempotencyLookupToAuthenticatedMerchant() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.CREATED);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "shared-key");

        verify(paymentRepository, times(2)).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "shared-key");
        assertEquals("shared-key", result.payment().getIdempotencyKey());
        assertFalse(result.replayed());
    }

    @Test
    void createPaymentTreatsMissingOrCrossMerchantOrderAsNotFound() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        orderId,
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> paymentService.createPayment(authenticatedMerchant, orderId, "new-key"));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentRejectsOrderReturnedWithDifferentMerchant() {
        Merchant authenticatedMerchant = persistedMerchant();
        Merchant differentMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(differentMerchant, OrderStatus.CREATED);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));

        assertThrows(
                OrderNotFoundException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "new-key"));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentRejectsDifferentKeyWhenOrderAlreadyHasPendingPayment() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(true);

        assertThrows(
                PaymentAlreadyPendingException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "new-key"));

        verify(paymentRepository, times(2)).findByMerchantIdAndIdempotencyKey(
                authenticatedMerchant.getId(),
                "new-key");
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentRejectsSucceededPayment() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(false);
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCEEDED))
                .thenReturn(true);

        assertThrows(
                OrderAlreadyPaidException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "new-key"));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentRejectsPaidOrderAsAlreadyPaid() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAID);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));

        assertThrows(
                OrderAlreadyPaidException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "new-key"));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @ParameterizedTest
    @MethodSource("invalidOrderStatuses")
    void createPaymentRejectsOrderStatusesThatCannotAcceptPayment(OrderStatus status) {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, status);
        when(orderRepository.findForUpdateByIdAndMerchantId(
                        order.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));

        assertThrows(
                OrderInvalidStateException.class,
                () -> paymentService.createPayment(
                        authenticatedMerchant,
                        order.getId(),
                        "new-key"));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void simulatePaymentSucceedsAndPersistsTerminalSnapshotEvent() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment payment = persistedPayment(order, "simulate-success-key");
        when(paymentRepository.findByIdAndMerchantId(
                        payment.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentService.simulatePayment(
                authenticatedMerchant,
                payment.getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        ArgumentCaptor<WebhookEvent> eventCaptor = ArgumentCaptor.forClass(WebhookEvent.class);
        assertSame(payment, result);
        assertEquals(PaymentStatus.SUCCEEDED, payment.getStatus());
        assertNotNull(payment.getCompletedAt());
        assertNull(payment.getFailureCode());
        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(paymentRepository).findByIdAndMerchantId(
                payment.getId(),
                authenticatedMerchant.getId());
        verify(paymentRepository).save(payment);
        verify(orderRepository).save(order);
        verify(orderRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
        verify(webhookEventRepository).save(eventCaptor.capture());

        WebhookEvent event = eventCaptor.getValue();
        assertSame(authenticatedMerchant, event.getMerchant());
        assertSame(payment, event.getPayment());
        assertEquals(WebhookEventType.PAYMENT_SUCCEEDED, event.getEventType());
        assertEquals(WebhookStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getLastAttemptAt());
        assertNull(event.getDeliveredAt());
        assertNull(event.getLastFailureCode());
        assertEquals(
                "pay_" + payment.getId(),
                event.getPayload().path("payment").path("id").stringValue());
        assertEquals(
                "ord_" + order.getId(),
                event.getPayload().path("payment").path("orderId").stringValue());
        assertEquals(1000L, event.getPayload().path("payment").path("amount").longValue());
        assertEquals("EUR", event.getPayload().path("payment").path("currency").stringValue());
        assertEquals("SUCCEEDED", event.getPayload().path("payment").path("status").stringValue());
        assertTrue(event.getPayload().path("payment").path("failureCode").isNull());
    }

    @ParameterizedTest
    @MethodSource("paymentFailureCodes")
    void simulatePaymentFailsAndPersistsRequestedFailureSnapshot(
            PaymentFailureCode failureCode) {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment payment = persistedPayment(order, "simulate-failure-key");
        when(paymentRepository.findByIdAndMerchantId(
                        payment.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        when(webhookEventRepository.save(any(WebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentService.simulatePayment(
                authenticatedMerchant,
                payment.getId(),
                PaymentSimulationOutcome.FAILED,
                failureCode);

        ArgumentCaptor<WebhookEvent> eventCaptor = ArgumentCaptor.forClass(WebhookEvent.class);
        assertSame(payment, result);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertNotNull(payment.getCompletedAt());
        assertEquals(failureCode, payment.getFailureCode());
        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
        verify(paymentRepository).findByIdAndMerchantId(
                payment.getId(),
                authenticatedMerchant.getId());
        verify(paymentRepository).save(payment);
        verify(orderRepository, never()).save(any(MerchantOrder.class));
        verify(orderRepository, never()).findForUpdateByIdAndMerchantId(any(), any());
        verify(webhookEventRepository).save(eventCaptor.capture());

        WebhookEvent event = eventCaptor.getValue();
        assertEquals(WebhookEventType.PAYMENT_FAILED, event.getEventType());
        assertEquals("FAILED", event.getPayload().path("payment").path("status").stringValue());
        assertEquals(
                failureCode.name(),
                event.getPayload().path("payment").path("failureCode").stringValue());
    }

    @Test
    void simulatePaymentTreatsMissingOrCrossMerchantPaymentAsNotFound() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndMerchantId(paymentId, authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                PaymentNotFoundException.class,
                () -> paymentService.simulatePayment(
                        authenticatedMerchant,
                        paymentId,
                        PaymentSimulationOutcome.SUCCEEDED,
                        null));

        verify(paymentRepository).findByIdAndMerchantId(
                paymentId,
                authenticatedMerchant.getId());
        verifyNoInteractions(orderRepository, webhookEventRepository);
    }

    @ParameterizedTest
    @MethodSource("terminalPaymentStatuses")
    void simulatePaymentRejectsTerminalPaymentWithoutCreatingEvent(PaymentStatus status) {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder order = persistedOrder(authenticatedMerchant, OrderStatus.PAYMENT_PENDING);
        Payment payment = persistedPayment(order, "terminal-payment-key");
        if (status == PaymentStatus.SUCCEEDED) {
            payment.markSucceeded(Instant.now());
        } else {
            payment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        }
        when(paymentRepository.findByIdAndMerchantId(
                        payment.getId(),
                        authenticatedMerchant.getId()))
                .thenReturn(Optional.of(payment));

        assertThrows(
                PaymentInvalidStateException.class,
                () -> paymentService.simulatePayment(
                        authenticatedMerchant,
                        payment.getId(),
                        PaymentSimulationOutcome.SUCCEEDED,
                        null));

        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(orderRepository, webhookEventRepository);
    }

    @Test
    void simulatePaymentIsTransactional() throws NoSuchMethodException {
        Method simulatePayment = PaymentService.class.getMethod(
                "simulatePayment",
                Merchant.class,
                UUID.class,
                PaymentSimulationOutcome.class,
                PaymentFailureCode.class);

        assertTrue(simulatePayment.isAnnotationPresent(Transactional.class));
    }

    private static Stream<OrderStatus> invalidOrderStatuses() {
        return Stream.of(
                OrderStatus.CANCELLED,
                OrderStatus.PARTIALLY_REFUNDED,
                OrderStatus.REFUNDED);
    }

    private static Stream<PaymentFailureCode> paymentFailureCodes() {
        return Stream.of(
                PaymentFailureCode.PAYMENT_DECLINED,
                PaymentFailureCode.PROCESSING_ERROR);
    }

    private static Stream<PaymentStatus> terminalPaymentStatuses() {
        return Stream.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED);
    }

    private Merchant persistedMerchant() {
        Merchant merchant = new Merchant(
                "Alice Shop",
                UUID.randomUUID() + "@example.com",
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private MerchantOrder persistedOrder(Merchant merchant, OrderStatus status) {
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setStatus(status);
        return order;
    }

    private Payment persistedPayment(MerchantOrder order, String idempotencyKey) {
        Payment payment = new Payment(order, idempotencyKey);
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }
}
