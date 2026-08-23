package com.ledgerpay.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private WebhookDeliveryService webhookDeliveryService;

    @Test
    void processesDueBatchInRepositoryOrderAndIsolatesPerEventFailures() {
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        UUID thirdEventId = UUID.randomUUID();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        WebhookDeliveryWorker worker = new WebhookDeliveryWorker(
                webhookEventRepository,
                webhookDeliveryService,
                clock);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(webhookEventRepository.findDueEventIds(
                        org.mockito.ArgumentMatchers.eq(WebhookStatus.PENDING),
                        org.mockito.ArgumentMatchers.eq(3),
                        org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(30)),
                        org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(firstEventId, secondEventId, thirdEventId));
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(webhookDeliveryService).process(secondEventId);

        worker.processDueEvents();

        verify(webhookEventRepository).findDueEventIds(
                org.mockito.ArgumentMatchers.eq(WebhookStatus.PENDING),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(30)),
                pageableCaptor.capture());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(50, pageableCaptor.getValue().getPageSize());
        var orderedCalls = inOrder(webhookDeliveryService);
        orderedCalls.verify(webhookDeliveryService).process(firstEventId);
        orderedCalls.verify(webhookDeliveryService).process(secondEventId);
        orderedCalls.verify(webhookDeliveryService).process(thirdEventId);
    }
}
