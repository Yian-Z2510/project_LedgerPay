package com.ledgerpay.service;

import com.ledgerpay.entity.Payment;

public record PaymentCreationResult(Payment payment, boolean replayed) {
}
