package com.ledgerpay.exception;

public class InvalidOrderIdException extends RuntimeException {

    public InvalidOrderIdException() {
        super("Invalid order ID.");
    }
}
