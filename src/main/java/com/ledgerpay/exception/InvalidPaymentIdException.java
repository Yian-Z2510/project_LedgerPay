package com.ledgerpay.exception;

public class InvalidPaymentIdException extends RuntimeException {

    public InvalidPaymentIdException() {
        super("Invalid payment ID.");
    }
}
