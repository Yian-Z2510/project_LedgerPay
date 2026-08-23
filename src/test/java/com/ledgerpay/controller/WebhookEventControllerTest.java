package com.ledgerpay.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.ledgerpay.config.JacksonConfiguration;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookFailureCode;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.GlobalExceptionHandler;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.WebhookEventNotFoundException;
import com.ledgerpay.exception.WebhookInvalidStateException;
import com.ledgerpay.exception.WebhookUrlNotConfiguredException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;
import com.ledgerpay.service.WebhookEventService;
import com.ledgerpay.service.WebhookDeliveryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookEventController.class)
@Import({
        JacksonConfiguration.class,
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
class WebhookEventControllerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");
    private static final Instant LAST_ATTEMPT_AT = Instant.parse("2026-08-23T10:01:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-08-23T10:02:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WebhookEventService webhookEventService;

    @MockitoBean
    private WebhookDeliveryService webhookDeliveryService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void getWebhookEventReturnsCompletePublicResponse() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.readTree("""
                {"payment":{"id":"pay_123","status":"SUCCEEDED"}}
                """);
        WebhookEvent event = event(
                eventId,
                WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookStatus.DELIVERED,
                2,
                payload,
                CREATED_AT);
        when(event.getLastAttemptAt()).thenReturn(LAST_ATTEMPT_AT);
        when(event.getDeliveredAt()).thenReturn(DELIVERED_AT);
        when(event.getLastFailureCode()).thenReturn(WebhookFailureCode.CONNECTION_TIMEOUT);
        authenticate(merchant);
        when(webhookEventService.getWebhookEvent(merchant, eventId)).thenReturn(event);

        mockMvc.perform(get("/api/v1/webhook-events/evt_" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("evt_" + eventId))
                .andExpect(jsonPath("$.type").value("payment.succeeded"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.attemptCount").value(2))
                .andExpect(jsonPath("$.lastAttemptAt").value(LAST_ATTEMPT_AT.toString()))
                .andExpect(jsonPath("$.deliveredAt").value(DELIVERED_AT.toString()))
                .andExpect(jsonPath("$.lastFailureCode").value("CONNECTION_TIMEOUT"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.data.payment.id").value("pay_123"))
                .andExpect(jsonPath("$.data.payment.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.merchantId").doesNotExist())
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andExpect(jsonPath("$.refund").doesNotExist());

        verify(webhookEventService).getWebhookEvent(merchant, eventId);
    }

    @ParameterizedTest
    @MethodSource("publicEventTypes")
    void mapsEveryInternalEventTypeToPublicName(
            WebhookEventType eventType,
            String publicName) throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        WebhookEvent event = event(
                eventId,
                eventType,
                WebhookStatus.PENDING,
                0,
                objectMapper.readTree("{}"),
                CREATED_AT);
        authenticate(merchant);
        when(webhookEventService.getWebhookEvent(merchant, eventId)).thenReturn(event);

        mockMvc.perform(get("/api/v1/webhook-events/evt_" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(publicName));
    }

    @Test
    void missingOrCrossMerchantEventReturnsWebhookEventNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookEventService.getWebhookEvent(merchant, eventId))
                .thenThrow(new WebhookEventNotFoundException());

        mockMvc.perform(get("/api/v1/webhook-events/evt_" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEBHOOK_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Webhook event was not found."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pay_123e4567-e89b-12d3-a456-426614174000",
            "evt_not-a-uuid",
            "evt_1-1-1-1-1"
    })
    void rejectsInvalidOrNonCanonicalEventIdBeforeService(String eventId) throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/webhook-events/" + eventId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid webhook event ID."));

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void paymentHistoryPreservesServiceOrderAndPublicMapping() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        WebhookEvent newerRefundEvent = event(
                UUID.randomUUID(),
                WebhookEventType.REFUND_FAILED,
                WebhookStatus.PENDING,
                0,
                objectMapper.readTree("{\"refund\":{\"paymentId\":\"pay_123\"}}"),
                CREATED_AT.plusSeconds(60));
        WebhookEvent olderPaymentEvent = event(
                UUID.randomUUID(),
                WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookStatus.PENDING,
                0,
                objectMapper.readTree("{\"payment\":{\"id\":\"pay_123\"}}"),
                CREATED_AT);
        authenticate(merchant);
        when(webhookEventService.listPaymentWebhookEvents(merchant, paymentId))
                .thenReturn(List.of(newerRefundEvent, olderPaymentEvent));

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/webhook-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("evt_" + newerRefundEvent.getId()))
                .andExpect(jsonPath("$[0].type").value("refund.failed"))
                .andExpect(jsonPath("$[0].data.refund.paymentId").value("pay_123"))
                .andExpect(jsonPath("$[1].id").value("evt_" + olderPaymentEvent.getId()))
                .andExpect(jsonPath("$[1].type").value("payment.succeeded"));
    }

    @Test
    void ownedPaymentWithoutEventsReturnsEmptyArray() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookEventService.listPaymentWebhookEvents(merchant, paymentId))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/webhook-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void missingOrCrossMerchantPaymentReturnsPaymentNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookEventService.listPaymentWebhookEvents(merchant, paymentId))
                .thenThrow(new PaymentNotFoundException());

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/webhook-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment was not found."));
    }

    @Test
    void webhookQueryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/webhook-events/evt_" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid or missing API credentials."));

        verifyNoInteractions(webhookEventService);
    }

    @Test
    void manualRetryReturnsLatestEventAfterRealRemoteFailure() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        WebhookEvent event = event(
                eventId,
                WebhookEventType.PAYMENT_FAILED,
                WebhookStatus.FAILED,
                4,
                objectMapper.readTree("{\"payment\":{\"status\":\"FAILED\"}}"),
                CREATED_AT);
        when(event.getLastAttemptAt()).thenReturn(LAST_ATTEMPT_AT);
        when(event.getLastFailureCode()).thenReturn(WebhookFailureCode.HTTP_ERROR);
        authenticate(merchant);
        when(webhookDeliveryService.retry(merchant, eventId)).thenReturn(event);

        mockMvc.perform(post("/api/v1/webhook-events/evt_" + eventId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("evt_" + eventId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attemptCount").value(4))
                .andExpect(jsonPath("$.lastFailureCode").value("HTTP_ERROR"));

        verify(webhookDeliveryService).retry(merchant, eventId);
    }

    @Test
    void manualRetryUsesNotFoundContractForMissingOrCrossMerchantEvent() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookDeliveryService.retry(merchant, eventId))
                .thenThrow(new WebhookEventNotFoundException());

        mockMvc.perform(post("/api/v1/webhook-events/evt_" + eventId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEBHOOK_EVENT_NOT_FOUND"));
    }

    @Test
    void manualRetryMapsInvalidStateAndMissingUrlToConflictContracts() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID invalidStateEventId = UUID.randomUUID();
        UUID missingUrlEventId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookDeliveryService.retry(merchant, invalidStateEventId))
                .thenThrow(new WebhookInvalidStateException());
        when(webhookDeliveryService.retry(merchant, missingUrlEventId))
                .thenThrow(new WebhookUrlNotConfiguredException());

        mockMvc.perform(post("/api/v1/webhook-events/evt_"
                        + invalidStateEventId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_INVALID_STATE"));
        mockMvc.perform(post("/api/v1/webhook-events/evt_"
                        + missingUrlEventId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_URL_NOT_CONFIGURED"));
    }

    @Test
    void manualPreHttpProcessingFailureUsesGenericInternalError() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID eventId = UUID.randomUUID();
        authenticate(merchant);
        when(webhookDeliveryService.retry(merchant, eventId))
                .thenThrow(new IllegalStateException("processing failed"));

        mockMvc.perform(post("/api/v1/webhook-events/evt_" + eventId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void manualRetryPreservesIdValidationAndAuthenticationContracts() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/webhook-events/evt_not-a-uuid/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/webhook-events/evt_" + UUID.randomUUID() + "/retry"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private WebhookEvent event(
            UUID id,
            WebhookEventType eventType,
            WebhookStatus status,
            int attemptCount,
            JsonNode payload,
            Instant createdAt) {
        WebhookEvent event = mock(WebhookEvent.class);
        when(event.getId()).thenReturn(id);
        when(event.getEventType()).thenReturn(eventType);
        when(event.getStatus()).thenReturn(status);
        when(event.getAttemptCount()).thenReturn(attemptCount);
        when(event.getCreatedAt()).thenReturn(createdAt);
        when(event.getPayload()).thenReturn(payload);
        return event;
    }

    private void authenticate(Merchant merchant) {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
    }

    private Merchant authenticatedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", API_KEY_HASH);
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private static List<Arguments> publicEventTypes() {
        return List.of(
                Arguments.of(WebhookEventType.PAYMENT_SUCCEEDED, "payment.succeeded"),
                Arguments.of(WebhookEventType.PAYMENT_FAILED, "payment.failed"),
                Arguments.of(WebhookEventType.REFUND_SUCCEEDED, "refund.succeeded"),
                Arguments.of(WebhookEventType.REFUND_FAILED, "refund.failed"));
    }
}
