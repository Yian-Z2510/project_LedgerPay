package com.ledgerpay.exception;

public class InvalidWebhookEventIdException extends RuntimeException {

    public InvalidWebhookEventIdException() {
        super("Invalid webhook event ID.");
    }
}
