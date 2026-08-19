package com.ledgerpay.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PaymentLifecyclePostgresAcceptanceTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> merchantIds = new ArrayList<>();

    @AfterEach
    void removePersistedTestData() {
        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void successfulPaymentPersistsPaidOrderAndDurableSucceededWebhookEvent() {
        Merchant merchant = createMerchant("Successful Lifecycle Merchant");
        MerchantOrder order = createOrder(merchant);

        PaymentCreationResult creation = paymentService.createPayment(
                merchant,
                order.getId(),
                "successful-lifecycle-key");

        assertTrue(!creation.replayed());
        assertEquals(PaymentStatus.PENDING, loadPayment(creation.payment().getId()).getStatus());
        assertEquals(OrderStatus.PAYMENT_PENDING, loadOrder(order.getId()).getStatus());

        paymentService.simulatePayment(
                merchant,
                creation.payment().getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        Payment persistedPayment = loadPayment(creation.payment().getId());
        MerchantOrder persistedOrder = loadOrder(order.getId());
        WebhookEvent persistedEvent = loadOnlyWebhookEvent(creation.payment().getId());

        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertNotNull(persistedPayment.getCompletedAt());
        assertNull(persistedPayment.getFailureCode());
        assertEquals(OrderStatus.PAID, persistedOrder.getStatus());
        assertEquals(merchant.getId(), persistedEvent.getMerchant().getId());
        assertEquals(persistedPayment.getId(), persistedEvent.getPayment().getId());
        assertEquals(WebhookEventType.PAYMENT_SUCCEEDED, persistedEvent.getEventType());
        assertEquals(WebhookStatus.PENDING, persistedEvent.getStatus());
        assertEquals(0, persistedEvent.getAttemptCount());
        assertNotNull(persistedEvent.getCreatedAt());
        assertEquals(
                "pay_" + persistedPayment.getId(),
                persistedEvent.getPayload().path("payment").path("id").stringValue());
        assertEquals(
                "ord_" + persistedOrder.getId(),
                persistedEvent.getPayload().path("payment").path("orderId").stringValue());
        assertEquals(
                persistedPayment.getAmount().longValue(),
                persistedEvent.getPayload().path("payment").path("amount").longValue());
        assertEquals(
                "EUR",
                persistedEvent.getPayload().path("payment").path("currency").stringValue());
        assertEquals(
                "SUCCEEDED",
                persistedEvent.getPayload().path("payment").path("status").stringValue());
        assertTrue(persistedEvent.getPayload().path("payment").path("failureCode").isNull());
    }

    @Test
    void twoFailedAttemptsRemainInHistoryBeforeThirdAttemptSucceeds() {
        Merchant merchant = createMerchant("Retry Lifecycle Merchant");
        MerchantOrder order = createOrder(merchant);

        Payment firstPayment = createAndSimulate(
                merchant,
                order,
                "retry-key-a",
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PAYMENT_DECLINED);
        assertEquals(OrderStatus.PAYMENT_PENDING, loadOrder(order.getId()).getStatus());

        Payment secondPayment = createAndSimulate(
                merchant,
                order,
                "retry-key-b",
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PROCESSING_ERROR);
        assertEquals(OrderStatus.PAYMENT_PENDING, loadOrder(order.getId()).getStatus());

        Payment thirdPayment = createAndSimulate(
                merchant,
                order,
                "retry-key-c",
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        assertNotEquals(firstPayment.getId(), secondPayment.getId());
        assertNotEquals(secondPayment.getId(), thirdPayment.getId());
        assertEquals(OrderStatus.PAID, loadOrder(order.getId()).getStatus());
        assertEquals(2L, countPayments(order.getId(), PaymentStatus.FAILED));
        assertEquals(0L, countPayments(order.getId(), PaymentStatus.PENDING));
        assertEquals(1L, countPayments(order.getId(), PaymentStatus.SUCCEEDED));

        List<Payment> history = paymentService.listPaymentsForOrder(merchant, order.getId());

        assertEquals(
                List.of(thirdPayment.getId(), secondPayment.getId(), firstPayment.getId()),
                history.stream().map(Payment::getId).toList());
        assertEquals(
                List.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.FAILED),
                history.stream().map(Payment::getStatus).toList());
        assertEquals(PaymentFailureCode.PROCESSING_ERROR, history.get(1).getFailureCode());
        assertEquals(PaymentFailureCode.PAYMENT_DECLINED, history.get(2).getFailureCode());
        assertTrue(history.stream()
                .allMatch(payment -> payment.getOrder().getId().equals(order.getId())));
    }

    private Payment createAndSimulate(
            Merchant merchant,
            MerchantOrder order,
            String idempotencyKey,
            PaymentSimulationOutcome outcome,
            PaymentFailureCode failureCode) {
        PaymentCreationResult creation = paymentService.createPayment(
                merchant,
                order.getId(),
                idempotencyKey);
        assertTrue(!creation.replayed());
        paymentService.simulatePayment(
                merchant,
                creation.payment().getId(),
                outcome,
                failureCode);
        return loadPayment(creation.payment().getId());
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

    private MerchantOrder createOrder(Merchant merchant) {
        return orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
    }

    private MerchantOrder loadOrder(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private Payment loadPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private WebhookEvent loadOnlyWebhookEvent(UUID paymentId) {
        List<UUID> eventIds = jdbcTemplate.query(
                "SELECT id FROM webhook_event WHERE payment_id = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                paymentId);
        assertEquals(1, eventIds.size());
        return webhookEventRepository.findById(eventIds.getFirst()).orElseThrow();
    }

    private long countPayments(UUID orderId, PaymentStatus status) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payment
                WHERE merchant_order_id = ? AND status = ?
                """,
                Long.class,
                orderId,
                status.name());
    }
}
