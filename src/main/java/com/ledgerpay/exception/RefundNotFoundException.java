package com.ledgerpay.exception;

public class RefundNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Refund was not found.";

    public RefundNotFoundException() {
        super(MESSAGE);
    }
}
