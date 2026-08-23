package com.ledgerpay.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.WebhookEventResponse;
import com.ledgerpay.dto.WebhookEventTypeMapper;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.exception.InvalidPaymentIdException;
import com.ledgerpay.exception.InvalidWebhookEventIdException;
import com.ledgerpay.service.WebhookEventService;

@RestController
@RequestMapping("/api/v1")
public class WebhookEventController {

    private static final String EVENT_ID_PREFIX = "evt_";
    private static final String PAYMENT_ID_PREFIX = "pay_";

    private final WebhookEventService webhookEventService;

    public WebhookEventController(WebhookEventService webhookEventService) {
        this.webhookEventService = webhookEventService;
    }

    @GetMapping("/webhook-events/{eventId}")
    public WebhookEventResponse getWebhookEvent(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String eventId) {
        WebhookEvent event = webhookEventService.getWebhookEvent(
                authenticatedMerchant,
                parseEventId(eventId));
        return toWebhookEventResponse(event);
    }

    @GetMapping("/payments/{paymentId}/webhook-events")
    public List<WebhookEventResponse> listPaymentWebhookEvents(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String paymentId) {
        return webhookEventService.listPaymentWebhookEvents(
                        authenticatedMerchant,
                        parsePaymentId(paymentId))
                .stream()
                .map(this::toWebhookEventResponse)
                .toList();
    }

    private WebhookEventResponse toWebhookEventResponse(WebhookEvent event) {
        return new WebhookEventResponse(
                EVENT_ID_PREFIX + event.getId(),
                WebhookEventTypeMapper.toPublicName(event.getEventType()),
                event.getStatus(),
                event.getAttemptCount(),
                event.getLastAttemptAt(),
                event.getDeliveredAt(),
                event.getLastFailureCode(),
                event.getCreatedAt(),
                event.getPayload());
    }

    private UUID parseEventId(String eventId) {
        if (!eventId.startsWith(EVENT_ID_PREFIX)) {
            throw new InvalidWebhookEventIdException();
        }

        String uuidValue = eventId.substring(EVENT_ID_PREFIX.length());
        try {
            UUID eventUuid = UUID.fromString(uuidValue);
            if (!eventUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidWebhookEventIdException();
            }
            return eventUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookEventIdException();
        }
    }

    private UUID parsePaymentId(String paymentId) {
        if (!paymentId.startsWith(PAYMENT_ID_PREFIX)) {
            throw new InvalidPaymentIdException();
        }

        String uuidValue = paymentId.substring(PAYMENT_ID_PREFIX.length());
        try {
            UUID paymentUuid = UUID.fromString(uuidValue);
            if (!paymentUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidPaymentIdException();
            }
            return paymentUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidPaymentIdException();
        }
    }
}
