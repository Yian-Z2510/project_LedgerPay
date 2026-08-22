package com.ledgerpay.exception;

public class RefundInvalidStateException extends RuntimeException {

    public RefundInvalidStateException() {
        super("Refund is no longer pending.");
    }
}
