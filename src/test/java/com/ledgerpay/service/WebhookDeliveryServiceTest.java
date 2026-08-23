package com.ledgerpay.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.dto.WebhookDeliveryRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookFailureCode;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");
    private static final Instant ATTEMPT_STARTED_AT = Instant.parse("2026-08-23T10:01:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-23T10:01:02Z");
    private static final String WEBHOOK_URL = "https://merchant.example.com/webhooks";

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private WebhookHttpClient webhookHttpClient;

    private WebhookDeliveryService webhookDeliveryService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        webhookDeliveryService = new WebhookDeliveryService(
                webhookEventRepository,
                webhookHttpClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void successfulAttemptUsesCurrentMerchantUrlAndStablePersistedEnvelope() {
        ObjectNode persistedPayload = objectMapper.createObjectNode();
        persistedPayload.putObject("payment")
                .put("id", "pay_snapshot")
                .put("status", "SUCCEEDED");
        WebhookEvent event = pendingEvent(WEBHOOK_URL, persistedPayload);
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.succeeded(
                        ATTEMPT_STARTED_AT,
                        COMPLETED_AT));
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        ArgumentCaptor<WebhookDeliveryRequest> requestCaptor =
                ArgumentCaptor.forClass(WebhookDeliveryRequest.class);
        verify(webhookHttpClient).post(
                org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                requestCaptor.capture());
        WebhookDeliveryRequest request = requestCaptor.getValue();
        assertEquals("evt_" + event.getId(), request.id());
        assertEquals("payment.succeeded", request.type());
        assertEquals(CREATED_AT, request.createdAt());
        assertEquals(persistedPayload, request.data());
        assertEquals(WebhookStatus.DELIVERED, result.getStatus());
        assertEquals(1, result.getAttemptCount());
        assertEquals(ATTEMPT_STARTED_AT, result.getLastAttemptAt());
        assertEquals(COMPLETED_AT, result.getDeliveredAt());
        assertNull(result.getLastFailureCode());
    }

    @Test
    void failedActualAttemptIncrementsOnceAndRemainsPending() {
        WebhookEvent event = pendingEvent(WEBHOOK_URL, objectMapper.createObjectNode());
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.requestFailed(
                        ATTEMPT_STARTED_AT,
                        WebhookFailureCode.HTTP_ERROR));
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        assertEquals(WebhookStatus.PENDING, result.getStatus());
        assertEquals(1, result.getAttemptCount());
        assertEquals(ATTEMPT_STARTED_AT, result.getLastAttemptAt());
        assertNull(result.getDeliveredAt());
        assertEquals(WebhookFailureCode.HTTP_ERROR, result.getLastFailureCode());
    }

    @Test
    void connectionFailureUsesConnectionTimeoutCode() {
        WebhookEvent event = pendingEvent(WEBHOOK_URL, objectMapper.createObjectNode());
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.requestFailed(
                        ATTEMPT_STARTED_AT,
                        WebhookFailureCode.CONNECTION_TIMEOUT));
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        assertEquals(1, result.getAttemptCount());
        assertEquals(
                WebhookFailureCode.CONNECTION_TIMEOUT,
                result.getLastFailureCode());
    }

    @Test
    void laterSuccessPreservesHistoricalFailureCode() {
        WebhookEvent event = pendingEvent(WEBHOOK_URL, objectMapper.createObjectNode());
        event.recordDeliveryFailed(
                ATTEMPT_STARTED_AT.minusSeconds(30),
                WebhookFailureCode.HTTP_ERROR);
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.succeeded(
                        ATTEMPT_STARTED_AT,
                        COMPLETED_AT));
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        assertEquals(WebhookStatus.DELIVERED, result.getStatus());
        assertEquals(2, result.getAttemptCount());
        assertEquals(WebhookFailureCode.HTTP_ERROR, result.getLastFailureCode());
    }

    @Test
    void processingFailureDoesNotCountAnHttpAttempt() {
        WebhookEvent event = pendingEvent(WEBHOOK_URL, objectMapper.createObjectNode());
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.processingFailed());
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        assertEquals(WebhookStatus.PENDING, result.getStatus());
        assertEquals(0, result.getAttemptCount());
        assertNull(result.getLastAttemptAt());
        assertEquals(WebhookFailureCode.PROCESSING_ERROR, result.getLastFailureCode());
    }

    @Test
    void missingWebhookUrlSkipsHttpAndFailsWithoutChangingAttemptMetadata() {
        WebhookEvent event = pendingEvent(null, objectMapper.createObjectNode());
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookEventRepository.save(event)).thenReturn(event);

        WebhookEvent result = webhookDeliveryService.process(event.getId());

        verify(webhookHttpClient, never()).post(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class));
        assertEquals(WebhookStatus.FAILED, result.getStatus());
        assertEquals(0, result.getAttemptCount());
        assertNull(result.getLastAttemptAt());
        assertNull(result.getDeliveredAt());
        assertEquals(
                WebhookFailureCode.WEBHOOK_URL_NOT_CONFIGURED,
                result.getLastFailureCode());
    }

    @Test
    void terminalEventsAreNoOp() {
        WebhookEvent deliveredEvent = pendingEvent(
                WEBHOOK_URL,
                objectMapper.createObjectNode());
        deliveredEvent.recordDeliverySucceeded(ATTEMPT_STARTED_AT, COMPLETED_AT);
        WebhookEvent failedEvent = pendingEvent(
                null,
                objectMapper.createObjectNode());
        failedEvent.markWebhookUrlNotConfigured();
        when(webhookEventRepository.findForDeliveryById(deliveredEvent.getId()))
                .thenReturn(Optional.of(deliveredEvent));
        when(webhookEventRepository.findForDeliveryById(failedEvent.getId()))
                .thenReturn(Optional.of(failedEvent));

        assertSame(
                deliveredEvent,
                webhookDeliveryService.process(deliveredEvent.getId()));
        assertSame(
                failedEvent,
                webhookDeliveryService.process(failedEvent.getId()));

        verify(webhookHttpClient, never()).post(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class));
        verify(webhookEventRepository, never()).save(
                org.mockito.ArgumentMatchers.any(WebhookEvent.class));
    }

    @Test
    void persistenceFailureAfterRemoteSuccessPropagatesWithoutRelabelingOutcome() {
        WebhookEvent event = pendingEvent(WEBHOOK_URL, objectMapper.createObjectNode());
        DataAccessResourceFailureException persistenceFailure =
                new DataAccessResourceFailureException("database unavailable");
        when(webhookEventRepository.findForDeliveryById(event.getId()))
                .thenReturn(Optional.of(event));
        when(webhookHttpClient.post(
                        org.mockito.ArgumentMatchers.eq(WEBHOOK_URL),
                        org.mockito.ArgumentMatchers.any(WebhookDeliveryRequest.class)))
                .thenReturn(WebhookDeliveryResult.succeeded(
                        ATTEMPT_STARTED_AT,
                        COMPLETED_AT));
        when(webhookEventRepository.save(event)).thenThrow(persistenceFailure);

        DataAccessResourceFailureException thrown = assertThrows(
                DataAccessResourceFailureException.class,
                () -> webhookDeliveryService.process(event.getId()));

        assertSame(persistenceFailure, thrown);
        assertEquals(WebhookStatus.DELIVERED, event.getStatus());
        assertNull(event.getLastFailureCode());
    }

    @Test
    void deliveryRequestKeepsDefensiveCopyOfPersistedPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("value", "original");
        WebhookDeliveryRequest request = new WebhookDeliveryRequest(
                "evt_" + UUID.randomUUID(),
                "payment.succeeded",
                CREATED_AT,
                payload);

        payload.put("value", "changed");
        ((ObjectNode) request.data()).put("value", "changed-again");

        assertEquals("original", request.data().path("value").stringValue());
        assertTrue(request.data().isObject());
    }

    private WebhookEvent pendingEvent(String webhookUrl, ObjectNode payload) {
        Merchant merchant = new Merchant(
                "Delivery Merchant",
                UUID.randomUUID() + "@example.com",
                "a".repeat(64));
        merchant.setWebhookUrl(webhookUrl);
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        Payment payment = new Payment(order, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        WebhookEvent event = new WebhookEvent(
                payment,
                WebhookEventType.PAYMENT_SUCCEEDED,
                payload);
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "createdAt", CREATED_AT);
        return event;
    }
}
