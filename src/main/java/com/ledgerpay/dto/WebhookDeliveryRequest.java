package com.ledgerpay.dto;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record WebhookDeliveryRequest(
        String id,
        String type,
        Instant createdAt,
        JsonNode data) {

    public WebhookDeliveryRequest {
        if (data == null) {
            throw new IllegalArgumentException("Webhook delivery data must not be null.");
        }
        data = data.deepCopy();
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }
}
