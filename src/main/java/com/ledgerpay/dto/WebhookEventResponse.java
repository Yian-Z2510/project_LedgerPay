package com.ledgerpay.dto;

import java.time.Instant;

import com.ledgerpay.entity.WebhookFailureCode;
import com.ledgerpay.entity.WebhookStatus;

import tools.jackson.databind.JsonNode;

public record WebhookEventResponse(
        String id,
        String type,
        WebhookStatus status,
        Integer attemptCount,
        Instant lastAttemptAt,
        Instant deliveredAt,
        WebhookFailureCode lastFailureCode,
        Instant createdAt,
        JsonNode data) {
}
