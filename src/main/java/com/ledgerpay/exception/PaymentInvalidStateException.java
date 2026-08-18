package com.ledgerpay.exception;

public class PaymentInvalidStateException extends RuntimeException {

    private static final String MESSAGE = "Payment is no longer pending.";

    public PaymentInvalidStateException() {
        super(MESSAGE);
    }
}
