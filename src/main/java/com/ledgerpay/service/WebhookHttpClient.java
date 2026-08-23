package com.ledgerpay.service;

import com.ledgerpay.dto.WebhookDeliveryRequest;

public interface WebhookHttpClient {

    WebhookDeliveryResult post(
            String webhookUrl,
            WebhookDeliveryRequest request);
}
