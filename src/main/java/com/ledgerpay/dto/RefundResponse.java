package com.ledgerpay.dto;

import java.time.Instant;

import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;

public record RefundResponse(
        String id,
        String paymentId,
        Long amount,
        OrderCurrency currency,
        RefundReasonCode reasonCode,
        RefundStatus status,
        RefundFailureCode failureCode,
        Instant createdAt,
        Instant updatedAt) {
}
