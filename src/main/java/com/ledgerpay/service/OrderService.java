package com.ledgerpay.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.dto.UpdateOrderRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;

@Service
public class OrderService {

    private static final String ORDER_ID_PREFIX = "ord_";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public OrderService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public OrderResponse createOrder(
            Merchant authenticatedMerchant,
            CreateOrderRequest request) {
        MerchantOrder order = new MerchantOrder(authenticatedMerchant, request.amount());
        MerchantOrder savedOrder = orderRepository.saveAndFlush(order);
        return toOrderResponse(savedOrder);
    }

    public OrderResponse getOrder(Merchant authenticatedMerchant, UUID orderId) {
        MerchantOrder order = orderRepository.findByIdAndMerchantId(
                        orderId,
                        authenticatedMerchant.getId())
                .orElseThrow(OrderNotFoundException::new);
        return toOrderResponse(order);
    }

    public List<OrderResponse> listOrders(Merchant authenticatedMerchant) {
        return orderRepository.findByMerchantIdOrderByCreatedAtDesc(authenticatedMerchant.getId())
                .stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Transactional
    public OrderResponse updateOrder(
            Merchant authenticatedMerchant,
            UUID orderId,
            UpdateOrderRequest request) {
        MerchantOrder order = lockOwnedOrder(authenticatedMerchant, orderId);

        if (order.getStatus() != OrderStatus.CREATED
                || paymentRepository.existsByOrderId(orderId)) {
            throw new OrderInvalidStateException("Order is not editable.");
        }

        order.setAmount(request.amount());
        return toOrderResponse(orderRepository.saveAndFlush(order));
    }

    @Transactional
    public OrderResponse cancelOrder(Merchant authenticatedMerchant, UUID orderId) {
        MerchantOrder order = lockOwnedOrder(authenticatedMerchant, orderId);

        boolean cancellationAllowed = order.getStatus() == OrderStatus.CREATED
                || (order.getStatus() == OrderStatus.PAYMENT_PENDING
                        && !paymentRepository.existsByOrderIdAndStatus(
                                orderId,
                                PaymentStatus.PENDING));
        if (!cancellationAllowed) {
            throw new OrderInvalidStateException("Order cancellation is not allowed.");
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        return toOrderResponse(orderRepository.saveAndFlush(order));
    }

    private MerchantOrder lockOwnedOrder(Merchant authenticatedMerchant, UUID orderId) {
        return orderRepository.findForUpdateByIdAndMerchantId(
                        orderId,
                        authenticatedMerchant.getId())
                .orElseThrow(OrderNotFoundException::new);
    }

    private OrderResponse toOrderResponse(MerchantOrder order) {
        return new OrderResponse(
                ORDER_ID_PREFIX + order.getId(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getCancelledAt(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
