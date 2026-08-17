package com.ledgerpay.exception;

public class PaymentAlreadyPendingException extends RuntimeException {

    private static final String MESSAGE = "Order already has a pending Payment.";

    public PaymentAlreadyPendingException() {
        super(MESSAGE);
    }
}
