package com.ledgerpay.exception;

public class InvalidRefundIdException extends RuntimeException {

    public InvalidRefundIdException() {
        super("Invalid refund ID.");
    }
}
