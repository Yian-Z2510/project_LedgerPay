package com.ledgerpay.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.dto.UpdateOrderRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-14T10:05:00Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, paymentRepository);
    }

    @Test
    void createOrderPersistsAuthenticatedMerchantOrderAndMapsResponse() {
        Merchant authenticatedMerchant = persistedMerchant();
        CreateOrderRequest request = new CreateOrderRequest(1000L);
        when(orderRepository.saveAndFlush(any(MerchantOrder.class))).thenAnswer(invocation -> {
            MerchantOrder order = invocation.getArgument(0);
            setPersistenceFields(order, UUID.randomUUID(), CREATED_AT, UPDATED_AT);
            return order;
        });

        OrderResponse response = orderService.createOrder(authenticatedMerchant, request);

        ArgumentCaptor<MerchantOrder> captor = ArgumentCaptor.forClass(MerchantOrder.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        MerchantOrder savedOrder = captor.getValue();
        assertSame(authenticatedMerchant, savedOrder.getMerchant());
        assertEquals(1000L, savedOrder.getAmount());
        assertEquals(OrderCurrency.EUR, response.currency());
        assertEquals(OrderStatus.CREATED, response.status());
        assertTrue(response.id().startsWith("ord_"));
        assertNull(response.cancelledAt());
        assertNotNull(response.createdAt());
        assertNotNull(response.updatedAt());
    }

    @Test
    void getOrderReturnsOwnedOrderAndMapsResponse() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        MerchantOrder order = persistedOrder(authenticatedMerchant, orderId, 1500L, CREATED_AT);
        when(orderRepository.findByIdAndMerchantId(orderId, authenticatedMerchant.getId()))
                .thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(authenticatedMerchant, orderId);

        assertEquals("ord_" + orderId, response.id());
        assertEquals(1500L, response.amount());
        assertEquals(OrderCurrency.EUR, response.currency());
        assertEquals(OrderStatus.CREATED, response.status());
        assertNull(response.cancelledAt());
        assertEquals(CREATED_AT, response.createdAt());
        assertEquals(UPDATED_AT, response.updatedAt());
        verify(orderRepository).findByIdAndMerchantId(orderId, authenticatedMerchant.getId());
    }

    @Test
    void getOrderThrowsOrderNotFoundWhenOrderDoesNotExist() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndMerchantId(orderId, authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrder(authenticatedMerchant, orderId));

        verify(orderRepository).findByIdAndMerchantId(orderId, authenticatedMerchant.getId());
    }

    @Test
    void getOrderTreatsCrossMerchantOrderAsNotFound() {
        Merchant authenticatedMerchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndMerchantId(orderId, authenticatedMerchant.getId()))
                .thenReturn(Optional.empty());

        OrderNotFoundException exception = assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrder(authenticatedMerchant, orderId));

        assertEquals("Order was not found.", exception.getMessage());
        verify(orderRepository).findByIdAndMerchantId(orderId, authenticatedMerchant.getId());
    }

    @Test
    void listOrdersUsesMerchantScopeAndPreservesRepositoryOrdering() {
        Merchant authenticatedMerchant = persistedMerchant();
        MerchantOrder newerOrder = persistedOrder(
                authenticatedMerchant,
                UUID.randomUUID(),
                2000L,
                CREATED_AT.plusSeconds(1));
        MerchantOrder olderOrder = persistedOrder(
                authenticatedMerchant,
                UUID.randomUUID(),
                1000L,
                CREATED_AT);
        when(orderRepository.findByMerchantIdOrderByCreatedAtDesc(authenticatedMerchant.getId()))
                .thenReturn(List.of(newerOrder, olderOrder));

        List<OrderResponse> responses = orderService.listOrders(authenticatedMerchant);

        assertEquals(
                List.of("ord_" + newerOrder.getId(), "ord_" + olderOrder.getId()),
                responses.stream().map(OrderResponse::id).toList());
        assertEquals(List.of(2000L, 1000L), responses.stream().map(OrderResponse::amount).toList());
        verify(orderRepository).findByMerchantIdOrderByCreatedAtDesc(authenticatedMerchant.getId());
    }

    @Test
    void listOrdersReturnsEmptyListWhenMerchantHasNoOrders() {
        Merchant authenticatedMerchant = persistedMerchant();
        when(orderRepository.findByMerchantIdOrderByCreatedAtDesc(authenticatedMerchant.getId()))
                .thenReturn(List.of());

        List<OrderResponse> responses = orderService.listOrders(authenticatedMerchant);

        assertTrue(responses.isEmpty());
        verify(orderRepository).findByMerchantIdOrderByCreatedAtDesc(authenticatedMerchant.getId());
    }

    @Test
    void updateOrderChangesAmountAfterLockWhenCreatedAndNoPaymentEverExisted() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderId(order.getId())).thenReturn(false);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderResponse response = orderService.updateOrder(
                merchant,
                order.getId(),
                new UpdateOrderRequest(1200L));

        assertEquals(1200L, order.getAmount());
        assertEquals(1200L, response.amount());
        verify(orderRepository).findForUpdateByIdAndMerchantId(order.getId(), merchant.getId());
        verify(paymentRepository).existsByOrderId(order.getId());
        verify(orderRepository).saveAndFlush(order);
    }

    @Test
    void updateOrderRejectsNonCreatedOrderAfterLock() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));

        OrderInvalidStateException exception = assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.updateOrder(
                        merchant,
                        order.getId(),
                        new UpdateOrderRequest(1200L)));

        assertEquals("Order is not editable.", exception.getMessage());
        verify(paymentRepository, never()).existsByOrderId(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateOrderRejectsHistoricalFailedPayment() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderId(order.getId())).thenReturn(true);

        assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.updateOrder(
                        merchant,
                        order.getId(),
                        new UpdateOrderRequest(1200L)));

        assertEquals(1000L, order.getAmount());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateOrderRejectsPendingPayment() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderId(order.getId())).thenReturn(true);

        assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.updateOrder(
                        merchant,
                        order.getId(),
                        new UpdateOrderRequest(1200L)));

        verify(paymentRepository).existsByOrderId(order.getId());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateOrderTreatsCrossMerchantOrderAsNotFound() {
        Merchant merchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findForUpdateByIdAndMerchantId(orderId, merchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.updateOrder(
                        merchant,
                        orderId,
                        new UpdateOrderRequest(1200L)));

        verify(paymentRepository, never()).existsByOrderId(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelCreatedOrderSetsCancelledStatusAndTimestamp() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        Instant beforeCancellation = Instant.now();

        OrderResponse response = orderService.cancelOrder(merchant, order.getId());

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertNotNull(order.getCancelledAt());
        assertTrue(!order.getCancelledAt().isBefore(beforeCancellation));
        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals(order.getCancelledAt(), response.cancelledAt());
        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
        verify(orderRepository).saveAndFlush(order);
    }

    @Test
    void cancelPaymentPendingOrderWithOnlyHistoricalFailedPayments() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(false);
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderResponse response = orderService.cancelOrder(merchant, order.getId());

        assertEquals(OrderStatus.CANCELLED, response.status());
        assertNotNull(response.cancelledAt());
        verify(paymentRepository).existsByOrderIdAndStatus(
                order.getId(), PaymentStatus.PENDING);
    }

    @Test
    void cancelPaymentPendingOrderRejectsCurrentPendingPayment() {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(true);

        assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.cancelOrder(merchant, order.getId()));

        assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
        assertNull(order.getCancelledAt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"PAID", "PARTIALLY_REFUNDED", "REFUNDED", "CANCELLED"})
    void cancelRejectsTerminalOrderStatuses(OrderStatus status) {
        Merchant merchant = persistedMerchant();
        MerchantOrder order = persistedOrder(merchant, UUID.randomUUID(), 1000L, CREATED_AT);
        order.setStatus(status);
        when(orderRepository.findForUpdateByIdAndMerchantId(order.getId(), merchant.getId()))
                .thenReturn(Optional.of(order));

        OrderInvalidStateException exception = assertThrows(
                OrderInvalidStateException.class,
                () -> orderService.cancelOrder(merchant, order.getId()));

        assertEquals("Order cancellation is not allowed.", exception.getMessage());
        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelTreatsCrossMerchantOrderAsNotFound() {
        Merchant merchant = persistedMerchant();
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findForUpdateByIdAndMerchantId(orderId, merchant.getId()))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.cancelOrder(merchant, orderId));

        verify(paymentRepository, never()).existsByOrderIdAndStatus(any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    private Merchant persistedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", "a".repeat(64));
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private MerchantOrder persistedOrder(
            Merchant merchant,
            UUID orderId,
            Long amount,
            Instant createdAt) {
        MerchantOrder order = new MerchantOrder(merchant, amount);
        setPersistenceFields(order, orderId, createdAt, UPDATED_AT);
        return order;
    }

    private void setPersistenceFields(
            MerchantOrder order,
            UUID orderId,
            Instant createdAt,
            Instant updatedAt) {
        ReflectionTestUtils.setField(order, "id", orderId);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        ReflectionTestUtils.setField(order, "updatedAt", updatedAt);
    }
}
