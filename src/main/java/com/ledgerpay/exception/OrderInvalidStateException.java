package com.ledgerpay.exception;

public class OrderInvalidStateException extends RuntimeException {

    private static final String MESSAGE = "Order cannot accept a Payment in its current state.";

    public OrderInvalidStateException() {
        super(MESSAGE);
    }
}
