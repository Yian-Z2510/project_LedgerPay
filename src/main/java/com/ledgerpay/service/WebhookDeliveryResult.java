package com.ledgerpay.service;

import java.time.Instant;

import com.ledgerpay.entity.WebhookFailureCode;

public record WebhookDeliveryResult(
        boolean attempted,
        boolean successful,
        Instant attemptStartedAt,
        Instant completedAt,
        WebhookFailureCode failureCode) {

    public static WebhookDeliveryResult succeeded(
            Instant attemptStartedAt,
            Instant completedAt) {
        return new WebhookDeliveryResult(
                true,
                true,
                attemptStartedAt,
                completedAt,
                null);
    }

    public static WebhookDeliveryResult requestFailed(
            Instant attemptStartedAt,
            WebhookFailureCode failureCode) {
        return new WebhookDeliveryResult(
                true,
                false,
                attemptStartedAt,
                null,
                failureCode);
    }

    public static WebhookDeliveryResult processingFailed() {
        return new WebhookDeliveryResult(
                false,
                false,
                null,
                null,
                WebhookFailureCode.PROCESSING_ERROR);
    }
}
