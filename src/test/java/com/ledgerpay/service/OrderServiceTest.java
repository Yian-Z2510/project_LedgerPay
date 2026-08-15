package com.ledgerpay.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.repository.OrderRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-14T10:05:00Z");

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository);
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
