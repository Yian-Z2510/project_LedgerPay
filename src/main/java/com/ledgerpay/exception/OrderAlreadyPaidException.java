package com.ledgerpay.exception;

public class OrderAlreadyPaidException extends RuntimeException {

    private static final String MESSAGE = "Order already has a successful Payment.";

    public OrderAlreadyPaidException() {
        super(MESSAGE);
    }
}
