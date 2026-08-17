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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.exception.OrderAlreadyPaidException;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, orderRepository);
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
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentService.createPayment(
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
        inOrder.verify(paymentRepository).save(paymentCaptor.capture());
        inOrder.verify(orderRepository).save(order);

        Payment savedPayment = paymentCaptor.getValue();
        assertSame(savedPayment, result);
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
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentService.createPayment(
                authenticatedMerchant,
                order.getId(),
                "retry-after-failure-key");

        assertEquals(PaymentStatus.FAILED, historicalPayment.getStatus());
        assertEquals(PaymentStatus.PENDING, result.getStatus());
        assertEquals("retry-after-failure-key", result.getIdempotencyKey());
        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
        verify(paymentRepository).save(any(Payment.class));
        verify(orderRepository, never()).save(any(MerchantOrder.class));
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

        verifyNoInteractions(paymentRepository);
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

        verifyNoInteractions(paymentRepository);
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentRejectsCurrentPendingPayment() {
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

        verify(paymentRepository, never()).save(any(Payment.class));
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

        verify(paymentRepository, never()).save(any(Payment.class));
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

        verifyNoInteractions(paymentRepository);
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

        verifyNoInteractions(paymentRepository);
        verify(orderRepository, never()).save(any(MerchantOrder.class));
    }

    @Test
    void createPaymentIsTransactional() throws NoSuchMethodException {
        Method createPayment = PaymentService.class.getMethod(
                "createPayment",
                Merchant.class,
                UUID.class,
                String.class);

        assertTrue(createPayment.isAnnotationPresent(Transactional.class));
    }

    private static Stream<OrderStatus> invalidOrderStatuses() {
        return Stream.of(
                OrderStatus.CANCELLED,
                OrderStatus.PARTIALLY_REFUNDED,
                OrderStatus.REFUNDED);
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
}
