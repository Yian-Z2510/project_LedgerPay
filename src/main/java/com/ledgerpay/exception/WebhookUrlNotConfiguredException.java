package com.ledgerpay.exception;

public class WebhookUrlNotConfiguredException extends RuntimeException {

    public WebhookUrlNotConfiguredException() {
        super("Merchant webhook URL is not configured.");
    }
}
