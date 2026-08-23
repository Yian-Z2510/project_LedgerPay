package com.ledgerpay.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ledgerpay.dto.WebhookDeliveryRequest;
import com.ledgerpay.dto.WebhookEventTypeMapper;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.WebhookEventNotFoundException;
import com.ledgerpay.repository.WebhookEventRepository;

@Service
public class WebhookDeliveryService {

    private static final String EVENT_ID_PREFIX = "evt_";

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookHttpClient webhookHttpClient;

    public WebhookDeliveryService(
            WebhookEventRepository webhookEventRepository,
            WebhookHttpClient webhookHttpClient) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookHttpClient = webhookHttpClient;
    }

    public WebhookEvent process(UUID eventId) {
        WebhookEvent event = webhookEventRepository.findForDeliveryById(eventId)
                .orElseThrow(WebhookEventNotFoundException::new);

        if (event.getStatus() != WebhookStatus.PENDING) {
            return event;
        }

        String webhookUrl = event.getMerchant().getWebhookUrl();
        if (webhookUrl == null) {
            event.markWebhookUrlNotConfigured();
            return webhookEventRepository.save(event);
        }

        WebhookDeliveryRequest request = new WebhookDeliveryRequest(
                EVENT_ID_PREFIX + event.getId(),
                WebhookEventTypeMapper.toPublicName(event.getEventType()),
                event.getCreatedAt(),
                event.getPayload());
        WebhookDeliveryResult result = webhookHttpClient.post(webhookUrl, request);

        if (!result.attempted()) {
            event.recordProcessingFailure();
        } else if (result.successful()) {
            event.recordDeliverySucceeded(
                    result.attemptStartedAt(),
                    result.completedAt());
        } else {
            event.recordDeliveryFailed(
                    result.attemptStartedAt(),
                    result.failureCode());
        }

        return webhookEventRepository.save(event);
    }
}
