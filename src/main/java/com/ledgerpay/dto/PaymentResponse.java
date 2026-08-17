package com.ledgerpay.dto;

import java.time.Instant;

import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentStatus;

public record PaymentResponse(
        String id,
        String orderId,
        Long amount,
        OrderCurrency currency,
        PaymentStatus status,
        Long refundedAmount,
        Long pendingRefundAmount,
        Long availableRefundAmount,
        PaymentFailureCode failureCode,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
