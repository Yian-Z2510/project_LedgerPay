package com.ledgerpay.dto;

import com.ledgerpay.entity.WebhookEventType;

public final class WebhookEventTypeMapper {

    private WebhookEventTypeMapper() {
    }

    public static String toPublicName(WebhookEventType eventType) {
        return switch (eventType) {
            case PAYMENT_SUCCEEDED -> "payment.succeeded";
            case PAYMENT_FAILED -> "payment.failed";
            case REFUND_SUCCEEDED -> "refund.succeeded";
            case REFUND_FAILED -> "refund.failed";
        };
    }
}
