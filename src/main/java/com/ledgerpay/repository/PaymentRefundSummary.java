package com.ledgerpay.repository;

public record PaymentRefundSummary(
        long amount,
        long refundedAmount,
        long pendingRefundAmount) {
}
