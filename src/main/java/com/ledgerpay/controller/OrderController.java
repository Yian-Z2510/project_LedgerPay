package com.ledgerpay.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.dto.UpdateOrderRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.exception.InvalidOrderIdException;
import com.ledgerpay.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private static final String ORDER_ID_PREFIX = "ord_";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(authenticatedMerchant, request);
    }

    @GetMapping("/orders")
    public List<OrderResponse> listOrders(
            @AuthenticationPrincipal Merchant authenticatedMerchant) {
        return orderService.listOrders(authenticatedMerchant);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String orderId) {
        return orderService.getOrder(authenticatedMerchant, parseOrderId(orderId));
    }

    @PatchMapping("/orders/{orderId}")
    public OrderResponse updateOrder(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String orderId,
            @Valid @RequestBody UpdateOrderRequest request) {
        return orderService.updateOrder(
                authenticatedMerchant,
                parseOrderId(orderId),
                request);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String orderId) {
        return orderService.cancelOrder(authenticatedMerchant, parseOrderId(orderId));
    }

    private UUID parseOrderId(String orderId) {
        if (!orderId.startsWith(ORDER_ID_PREFIX)) {
            throw new InvalidOrderIdException();
        }

        String uuidValue = orderId.substring(ORDER_ID_PREFIX.length());
        try {
            UUID orderUuid = UUID.fromString(uuidValue);
            if (!orderUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidOrderIdException();
            }
            return orderUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrderIdException();
        }
    }
}
