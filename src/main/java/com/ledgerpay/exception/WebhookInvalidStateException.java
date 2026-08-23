package com.ledgerpay.exception;

public class WebhookInvalidStateException extends RuntimeException {

    public WebhookInvalidStateException() {
        super("Webhook event is not eligible for manual retry.");
    }
}
