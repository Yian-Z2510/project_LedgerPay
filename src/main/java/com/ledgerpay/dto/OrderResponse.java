package com.ledgerpay.dto;

import java.time.Instant;

import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;

public record OrderResponse(
        String id,
        Long amount,
        OrderCurrency currency,
        OrderStatus status,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {
}
