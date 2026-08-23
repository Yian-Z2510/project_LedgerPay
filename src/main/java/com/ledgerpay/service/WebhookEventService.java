package com.ledgerpay.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.WebhookEventNotFoundException;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;

    public WebhookEventService(
            WebhookEventRepository webhookEventRepository,
            PaymentRepository paymentRepository) {
        this.webhookEventRepository = webhookEventRepository;
        this.paymentRepository = paymentRepository;
    }

    public WebhookEvent getWebhookEvent(
            Merchant authenticatedMerchant,
            UUID eventId) {
        return webhookEventRepository.findByIdAndMerchantId(
                        eventId,
                        authenticatedMerchant.getId())
                .orElseThrow(WebhookEventNotFoundException::new);
    }

    public List<WebhookEvent> listPaymentWebhookEvents(
            Merchant authenticatedMerchant,
            UUID paymentId) {
        UUID merchantId = authenticatedMerchant.getId();
        if (!paymentRepository.existsByIdAndMerchantId(paymentId, merchantId)) {
            throw new PaymentNotFoundException();
        }

        return webhookEventRepository.findPaymentHistory(paymentId, merchantId);
    }
}
