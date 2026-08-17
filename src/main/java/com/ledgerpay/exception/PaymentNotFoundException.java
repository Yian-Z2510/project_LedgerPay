package com.ledgerpay.exception;

public class PaymentNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Payment was not found.";

    public PaymentNotFoundException() {
        super(MESSAGE);
    }
}
