package com.ledgerpay.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.WebhookEventNotFoundException;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEventServiceTest {

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private WebhookEventService webhookEventService;

    @BeforeEach
    void setUp() {
        webhookEventService = new WebhookEventService(
                webhookEventRepository,
                paymentRepository);
    }

    @Test
    void getWebhookEventUsesEventAndMerchantScope() {
        Merchant merchant = persistedMerchant();
        UUID eventId = UUID.randomUUID();
        WebhookEvent event = mock(WebhookEvent.class);
        when(webhookEventRepository.findByIdAndMerchantId(eventId, merchant.getId()))
                .thenReturn(Optional.of(event));

        WebhookEvent result = webhookEventService.getWebhookEvent(merchant, eventId);

        assertSame(event, result);
        verify(webhookEventRepository).findByIdAndMerchantId(eventId, merchant.getId());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void missingAndCrossMerchantWebhookEventsUseSameNotFoundBehavior() {
        Merchant merchant = persistedMerchant();
        UUID eventId = UUID.randomUUID();
        when(webhookEventRepository.findByIdAndMerchantId(eventId, merchant.getId()))
                .thenReturn(Optional.empty());

        WebhookEventNotFoundException exception = assertThrows(
                WebhookEventNotFoundException.class,
                () -> webhookEventService.getWebhookEvent(merchant, eventId));

        assertEquals("Webhook event was not found.", exception.getMessage());
        verify(webhookEventRepository).findByIdAndMerchantId(eventId, merchant.getId());
    }

    @Test
    void listPaymentWebhookEventsValidatesOwnershipBeforeHistoryQuery() {
        Merchant merchant = persistedMerchant();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.existsByIdAndMerchantId(paymentId, merchant.getId()))
                .thenReturn(false);

        assertThrows(
                PaymentNotFoundException.class,
                () -> webhookEventService.listPaymentWebhookEvents(merchant, paymentId));

        verify(paymentRepository).existsByIdAndMerchantId(paymentId, merchant.getId());
        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void listPaymentWebhookEventsReturnsMerchantScopedRepositoryOrder() {
        Merchant merchant = persistedMerchant();
        UUID paymentId = UUID.randomUUID();
        WebhookEvent newerEvent = mock(WebhookEvent.class);
        WebhookEvent olderEvent = mock(WebhookEvent.class);
        when(paymentRepository.existsByIdAndMerchantId(paymentId, merchant.getId()))
                .thenReturn(true);
        when(webhookEventRepository.findPaymentHistory(paymentId, merchant.getId()))
                .thenReturn(List.of(newerEvent, olderEvent));

        List<WebhookEvent> result = webhookEventService.listPaymentWebhookEvents(
                merchant,
                paymentId);

        assertEquals(List.of(newerEvent, olderEvent), result);
        verify(webhookEventRepository).findPaymentHistory(paymentId, merchant.getId());
    }

    @Test
    void ownedPaymentWithoutEventsReturnsEmptyHistory() {
        Merchant merchant = persistedMerchant();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.existsByIdAndMerchantId(paymentId, merchant.getId()))
                .thenReturn(true);
        when(webhookEventRepository.findPaymentHistory(paymentId, merchant.getId()))
                .thenReturn(List.of());

        List<WebhookEvent> result = webhookEventService.listPaymentWebhookEvents(
                merchant,
                paymentId);

        assertEquals(List.of(), result);
    }

    private Merchant persistedMerchant() {
        Merchant merchant = new Merchant(
                "Webhook Query Merchant",
                UUID.randomUUID() + "@example.com",
                "a".repeat(64));
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }
}
