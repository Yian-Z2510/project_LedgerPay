package com.ledgerpay.exception;

public class PaymentNotRefundableException extends RuntimeException {

    private static final String MESSAGE = "Payment has not succeeded and cannot be refunded.";

    public PaymentNotRefundableException() {
        super(MESSAGE);
    }
}
