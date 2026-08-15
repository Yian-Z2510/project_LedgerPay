package com.ledgerpay.exception;

public class OrderNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Order was not found.";

    public OrderNotFoundException() {
        super(MESSAGE);
    }
}
