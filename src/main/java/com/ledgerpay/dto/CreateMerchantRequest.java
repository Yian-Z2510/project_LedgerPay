package com.ledgerpay.dto;

import com.ledgerpay.validation.ValidWebhookUrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMerchantRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 254)
        String email,

        @Size(max = 2048)
        @ValidWebhookUrl
        String webhookUrl) {
}
