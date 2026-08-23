package com.ledgerpay.exception;

public class WebhookEventNotFoundException extends RuntimeException {

    public WebhookEventNotFoundException() {
        super("Webhook event was not found.");
    }
}
