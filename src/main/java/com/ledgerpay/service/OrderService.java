package com.ledgerpay.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.repository.OrderRepository;

@Service
public class OrderService {

    private static final String ORDER_ID_PREFIX = "ord_";

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
