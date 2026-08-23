package com.ledgerpay.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ledgerpay.dto.WebhookDeliveryRequest;
import com.ledgerpay.dto.WebhookEventTypeMapper;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.WebhookEventNotFoundException;
import com.ledgerpay.exception.WebhookInvalidStateException;
import com.ledgerpay.exception.WebhookUrlNotConfiguredException;
import com.ledgerpay.repository.WebhookEventRepository;

@Service
public class WebhookDeliveryService {

    private static final String EVENT_ID_PREFIX = "evt_";
    static final int MAXIMUM_AUTOMATIC_ATTEMPTS = 3;

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

        if (event.getStatus() != WebhookStatus.PENDING
                || event.getAttemptCount() >= MAXIMUM_AUTOMATIC_ATTEMPTS) {
            return event;
        }

        String webhookUrl = event.getMerchant().getWebhookUrl();
        if (webhookUrl == null) {
            event.markWebhookUrlNotConfigured();
            return webhookEventRepository.save(event);
        }

        WebhookDeliveryResult result = webhookHttpClient.post(
                webhookUrl,
                toDeliveryRequest(event));

        if (!result.attempted()) {
            event.recordProcessingFailure();
        } else if (result.successful()) {
            event.recordAutomaticDeliverySucceeded(
                    result.attemptStartedAt(),
                    result.completedAt());
        } else {
            event.recordAutomaticDeliveryFailed(
                    result.attemptStartedAt(),
                    result.failureCode(),
                    MAXIMUM_AUTOMATIC_ATTEMPTS);
        }

        return webhookEventRepository.save(event);
    }

    public WebhookEvent retry(
            Merchant authenticatedMerchant,
            UUID eventId) {
        WebhookEvent event = webhookEventRepository.findForDeliveryByIdAndMerchantId(
                        eventId,
                        authenticatedMerchant.getId())
                .orElseThrow(WebhookEventNotFoundException::new);

        if (event.getStatus() != WebhookStatus.FAILED) {
            throw new WebhookInvalidStateException();
        }

        String webhookUrl = event.getMerchant().getWebhookUrl();
        if (webhookUrl == null) {
            throw new WebhookUrlNotConfiguredException();
        }

        WebhookDeliveryResult result = webhookHttpClient.post(
                webhookUrl,
                toDeliveryRequest(event));
        if (!result.attempted()) {
            throw new IllegalStateException(
                    "Manual Webhook retry failed before an HTTP request began.");
        }

        if (result.successful()) {
            event.recordManualDeliverySucceeded(
                    result.attemptStartedAt(),
                    result.completedAt());
        } else {
            event.recordManualDeliveryFailed(
                    result.attemptStartedAt(),
                    result.failureCode());
        }

        return webhookEventRepository.save(event);
    }

    private WebhookDeliveryRequest toDeliveryRequest(WebhookEvent event) {
        return new WebhookDeliveryRequest(
                EVENT_ID_PREFIX + event.getId(),
                WebhookEventTypeMapper.toPublicName(event.getEventType()),
                event.getCreatedAt(),
                event.getPayload());
    }
}
