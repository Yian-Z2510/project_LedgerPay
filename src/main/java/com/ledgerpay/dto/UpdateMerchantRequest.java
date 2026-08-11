package com.ledgerpay.dto;

import com.ledgerpay.validation.ValidWebhookUrl;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public class UpdateMerchantRequest {

    @Size(max = 2048)
    @ValidWebhookUrl
    private String webhookUrl;

    @AssertTrue(message = "webhookUrl must be provided")
    private boolean webhookUrlPresent;

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.webhookUrlPresent = true;
    }

    public boolean hasWebhookUrl() {
        return webhookUrlPresent;
    }
}
