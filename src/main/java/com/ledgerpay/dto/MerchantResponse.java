package com.ledgerpay.dto;

import java.time.Instant;

import com.ledgerpay.entity.MerchantStatus;

public record MerchantResponse(
        String id,
        String name,
        String email,
        MerchantStatus status,
        String webhookUrl,
        Instant deactivatedAt,
        Instant createdAt,
        Instant updatedAt) {
}
