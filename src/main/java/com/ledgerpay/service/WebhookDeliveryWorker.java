package com.ledgerpay.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.repository.WebhookEventRepository;

@Component
@ConditionalOnProperty(
        name = "webhook.delivery.worker.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WebhookDeliveryWorker {

    static final long POLLING_DELAY_MILLISECONDS = 5_000L;
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(10);
    static final int BATCH_SIZE = 50;

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final Clock clock;

    @Autowired
    public WebhookDeliveryWorker(
            WebhookEventRepository webhookEventRepository,
            WebhookDeliveryService webhookDeliveryService) {
        this(webhookEventRepository, webhookDeliveryService, Clock.systemUTC());
    }

    WebhookDeliveryWorker(
            WebhookEventRepository webhookEventRepository,
            WebhookDeliveryService webhookDeliveryService,
            Clock clock) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookDeliveryService = webhookDeliveryService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = POLLING_DELAY_MILLISECONDS)
    public void processDueEvents() {
        Instant retryCutoff = clock.instant().minus(RETRY_INTERVAL);
        List<UUID> dueEventIds = webhookEventRepository.findDueEventIds(
                WebhookStatus.PENDING,
                WebhookDeliveryService.MAXIMUM_AUTOMATIC_ATTEMPTS,
                retryCutoff,
                PageRequest.of(0, BATCH_SIZE));

        for (UUID eventId : dueEventIds) {
            try {
                webhookDeliveryService.process(eventId);
            } catch (Exception exception) {
                LOGGER.error("Automatic Webhook delivery failed for event {}.", eventId, exception);
            }
        }
    }
}
