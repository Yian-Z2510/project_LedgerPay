package com.ledgerpay.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Invalid or missing Idempotency-Key header.");
    }
}
