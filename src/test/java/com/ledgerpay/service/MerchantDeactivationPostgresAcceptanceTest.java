package com.ledgerpay.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.MerchantStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.MerchantHasUnfinishedOperationsException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MerchantDeactivationPostgresAcceptanceTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> merchantIds = new ArrayList<>();

    @AfterEach
    void removePersistedTestData() {
        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM refund WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void pendingPaymentAloneBlocksDeactivation() {
        Merchant merchant = createMerchant("Pending Payment Deactivation");
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        paymentRepository.saveAndFlush(new Payment(order, "pending-payment-deactivation-key"));

        assertDeactivationRejected(merchant);
    }

    @Test
    void pendingRefundAloneBlocksDeactivation() {
        Merchant merchant = createMerchant("Pending Refund Deactivation");
        Payment payment = createSucceededPayment(merchant, "pending-refund-payment-key");
        refundRepository.saveAndFlush(new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "pending-refund-deactivation-key"));

        assertDeactivationRejected(merchant);
    }

    @Test
    void pendingWebhookEventAloneBlocksDeactivation() {
        Merchant merchant = createMerchant("Pending Webhook Deactivation");
        Payment payment = createSucceededPayment(merchant, "pending-webhook-payment-key");
        webhookEventRepository.saveAndFlush(new WebhookEvent(
                payment,
                WebhookEventType.PAYMENT_SUCCEEDED,
                objectMapper.createObjectNode()));

        assertDeactivationRejected(merchant);
    }

    @Test
    void terminalHistoryDoesNotBlockSuccessfulDeactivation() {
        Merchant merchant = createMerchant("Terminal History Deactivation");
        Payment failedPayment = createFailedPayment(merchant, "terminal-failed-payment-key");
        Payment succeededPayment = createSucceededPayment(
                merchant,
                "terminal-succeeded-payment-key");
        Refund failedRefund = new Refund(
                succeededPayment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "terminal-failed-refund-key");
        failedRefund.markFailed(RefundFailureCode.REFUND_PROCESSING_ERROR);
        refundRepository.saveAndFlush(failedRefund);
        WebhookEvent failedEvent = webhookEventRepository.saveAndFlush(new WebhookEvent(
                failedPayment,
                WebhookEventType.PAYMENT_FAILED,
                objectMapper.createObjectNode()));
        jdbcTemplate.update(
                """
                UPDATE webhook_event
                SET status = 'FAILED',
                    last_failure_code = 'WEBHOOK_URL_NOT_CONFIGURED'
                WHERE id = ?
                """,
                failedEvent.getId());

        assertFalse(paymentRepository.existsByMerchantIdAndStatus(
                merchant.getId(),
                PaymentStatus.PENDING));
        assertFalse(refundRepository.existsByMerchantIdAndStatus(
                merchant.getId(),
                RefundStatus.PENDING));
        assertFalse(webhookEventRepository.existsByMerchantIdAndStatus(
                merchant.getId(),
                WebhookStatus.PENDING));
        Instant beforeDeactivation = Instant.now();

        MerchantResponse response = merchantService.deactivate(merchant);

        Instant afterDeactivation = Instant.now();
        Merchant persistedMerchant = merchantRepository.findById(merchant.getId()).orElseThrow();
        assertEquals(MerchantStatus.INACTIVE, response.status());
        assertNotNull(response.deactivatedAt());
        assertFalse(response.deactivatedAt().isBefore(beforeDeactivation));
        assertFalse(response.deactivatedAt().isAfter(afterDeactivation));
        assertEquals(MerchantStatus.INACTIVE, persistedMerchant.getStatus());
        assertEquals(
                response.deactivatedAt().truncatedTo(ChronoUnit.MICROS),
                persistedMerchant.getDeactivatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
        merchantIds.add(merchant.getId());
        return merchant;
    }

    private Payment createSucceededPayment(Merchant merchant, String idempotencyKey) {
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment payment = new Payment(order, idempotencyKey);
        payment.markSucceeded(Instant.now());
        return paymentRepository.saveAndFlush(payment);
    }

    private Payment createFailedPayment(Merchant merchant, String idempotencyKey) {
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        Payment payment = new Payment(order, idempotencyKey);
        payment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, Instant.now());
        return paymentRepository.saveAndFlush(payment);
    }

    private void assertDeactivationRejected(Merchant merchant) {
        assertThrows(
                MerchantHasUnfinishedOperationsException.class,
                () -> merchantService.deactivate(merchant));

        Merchant persistedMerchant = merchantRepository.findById(merchant.getId()).orElseThrow();
        assertEquals(MerchantStatus.ACTIVE, persistedMerchant.getStatus());
        assertNull(persistedMerchant.getDeactivatedAt());
        assertTrue(merchant.getStatus() == MerchantStatus.ACTIVE);
    }
}
