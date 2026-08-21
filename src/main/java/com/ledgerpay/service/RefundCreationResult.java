package com.ledgerpay.service;

import com.ledgerpay.entity.Refund;

public record RefundCreationResult(Refund refund, boolean replayed) {
}
