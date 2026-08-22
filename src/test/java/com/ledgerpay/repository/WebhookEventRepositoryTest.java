package com.ledgerpay.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class WebhookEventRepositoryTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-18T12:00:00Z");

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void savesAndReloadsPendingPaymentSucceededEvent() throws Exception {
        Merchant merchant = persistMerchant("Succeeded Event Merchant");
        Payment payment = persistSucceededPayment(merchant, "succeeded-event-payment");
        JsonNode payload = objectMapper.readTree("""
                {
                  "payment": {
                    "id": "pay_%s",
                    "orderId": "ord_%s",
                    "amount": 1000,
                    "currency": "EUR",
                    "status": "SUCCEEDED",
                    "failureCode": null
                  }
                }
                """.formatted(payment.getId(), payment.getOrder().getId()));

        WebhookEvent savedEvent = webhookEventRepository.saveAndFlush(
                new WebhookEvent(payment, WebhookEventType.PAYMENT_SUCCEEDED, payload));
        UUID eventId = savedEvent.getId();
        entityManager.clear();

        WebhookEvent reloadedEvent = webhookEventRepository.findById(eventId).orElseThrow();

        assertNotNull(reloadedEvent.getId());
        assertEquals(merchant.getId(), reloadedEvent.getMerchant().getId());
        assertEquals(payment.getId(), reloadedEvent.getPayment().getId());
        assertNull(reloadedEvent.getRefund());
        assertEquals(WebhookEventType.PAYMENT_SUCCEEDED, reloadedEvent.getEventType());
        assertEquals(payload, reloadedEvent.getPayload());
        assertEquals(WebhookStatus.PENDING, reloadedEvent.getStatus());
        assertEquals(0, reloadedEvent.getAttemptCount());
        assertNull(reloadedEvent.getLastAttemptAt());
        assertNull(reloadedEvent.getDeliveredAt());
        assertNull(reloadedEvent.getLastFailureCode());
        assertNotNull(reloadedEvent.getCreatedAt());
        assertNotNull(reloadedEvent.getUpdatedAt());
    }

    @Test
    void savesAndReloadsPaymentFailedPayload() throws Exception {
        Merchant merchant = persistMerchant("Failed Event Merchant");
        Payment payment = persistFailedPayment(merchant, "failed-event-payment");
        JsonNode payload = objectMapper.readTree("""
                {
                  "payment": {
                    "id": "pay_%s",
                    "orderId": "ord_%s",
                    "amount": 1000,
                    "currency": "EUR",
                    "status": "FAILED",
                    "failureCode": "PAYMENT_DECLINED"
                  }
                }
                """.formatted(payment.getId(), payment.getOrder().getId()));

        WebhookEvent savedEvent = webhookEventRepository.saveAndFlush(
                new WebhookEvent(payment, WebhookEventType.PAYMENT_FAILED, payload));
        UUID eventId = savedEvent.getId();
        entityManager.clear();

        WebhookEvent reloadedEvent = webhookEventRepository.findById(eventId).orElseThrow();

        assertEquals(WebhookEventType.PAYMENT_FAILED, reloadedEvent.getEventType());
        assertEquals(payload, reloadedEvent.getPayload());
        assertEquals(
                "PAYMENT_DECLINED",
                reloadedEvent.getPayload().path("payment").path("failureCode").stringValue());
    }

    @Test
    void savesAndReloadsPendingRefundSucceededEvent() throws Exception {
        Merchant merchant = persistMerchant("Refund Event Merchant");
        Payment payment = persistSucceededPayment(merchant, "refund-event-payment");
        Refund refund = persistRefund(payment, "refund-event-key");
        JsonNode payload = objectMapper.readTree("""
                {
                  "refund": {
                    "id": "re_%s",
                    "paymentId": "pay_%s",
                    "amount": 300,
                    "currency": "EUR",
                    "reasonCode": "CUSTOMER_REQUEST",
                    "status": "SUCCEEDED",
                    "failureCode": null
                  }
                }
                """.formatted(refund.getId(), payment.getId()));

        WebhookEvent savedEvent = webhookEventRepository.saveAndFlush(
                new WebhookEvent(refund, WebhookEventType.REFUND_SUCCEEDED, payload));
        UUID eventId = savedEvent.getId();
        entityManager.clear();

        WebhookEvent reloadedEvent = webhookEventRepository.findById(eventId).orElseThrow();

        assertEquals(merchant.getId(), reloadedEvent.getMerchant().getId());
        assertNull(reloadedEvent.getPayment());
        assertEquals(refund.getId(), reloadedEvent.getRefund().getId());
        assertEquals(WebhookEventType.REFUND_SUCCEEDED, reloadedEvent.getEventType());
        assertEquals(payload, reloadedEvent.getPayload());
        assertEquals(WebhookStatus.PENDING, reloadedEvent.getStatus());
        assertEquals(0, reloadedEvent.getAttemptCount());
    }

    @Test
    void rejectsEventReferencingBothPaymentAndRefund() {
        Merchant merchant = persistMerchant("Both Sources Merchant");
        Payment payment = persistSucceededPayment(merchant, "both-sources-payment");
        Refund refund = persistRefund(payment, "both-sources-refund");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSourcedWebhookEvent(
                        merchant.getId(),
                        "PAYMENT_SUCCEEDED",
                        payment.getId(),
                        refund.getId()));
    }

    @Test
    void rejectsEventReferencingNeitherPaymentNorRefund() {
        Merchant merchant = persistMerchant("No Source Merchant");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSourcedWebhookEvent(
                        merchant.getId(),
                        "PAYMENT_SUCCEEDED",
                        null,
                        null));
    }

    @Test
    void rejectsRefundEventWhoseMerchantDoesNotOwnRefund() {
        Merchant refundMerchant = persistMerchant("Refund Owner Merchant");
        Merchant eventMerchant = persistMerchant("Foreign Refund Event Merchant");
        Payment payment = persistSucceededPayment(refundMerchant, "owned-refund-payment");
        Refund refund = persistRefund(payment, "owned-refund-key");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSourcedWebhookEvent(
                        eventMerchant.getId(),
                        "REFUND_SUCCEEDED",
                        null,
                        refund.getId()));
    }

    @ParameterizedTest
    @MethodSource("invalidEventTypeSources")
    void rejectsEventTypeThatDoesNotMatchSource(boolean usePaymentSource, String eventType) {
        Merchant merchant = persistMerchant("Type Source Merchant");
        Payment payment = persistSucceededPayment(merchant, UUID.randomUUID().toString());
        Refund refund = persistRefund(payment, UUID.randomUUID().toString());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSourcedWebhookEvent(
                        merchant.getId(),
                        eventType,
                        usePaymentSource ? payment.getId() : null,
                        usePaymentSource ? null : refund.getId()));
    }

    @Test
    void rejectsDuplicateEventTypeForSameRefund() throws Exception {
        Merchant merchant = persistMerchant("Duplicate Refund Event Merchant");
        Payment payment = persistSucceededPayment(merchant, "duplicate-refund-event-payment");
        Refund refund = persistRefund(payment, "duplicate-refund-event-key");
        JsonNode payload = objectMapper.readTree("{\"refund\":{\"status\":\"SUCCEEDED\"}}");
        webhookEventRepository.saveAndFlush(
                new WebhookEvent(refund, WebhookEventType.REFUND_SUCCEEDED, payload));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> webhookEventRepository.saveAndFlush(
                        new WebhookEvent(
                                refund,
                                WebhookEventType.REFUND_SUCCEEDED,
                                payload)));
    }

    @Test
    void persistsDefensiveCopyOfSuppliedPayload() throws Exception {
        Merchant merchant = persistMerchant("Immutable Payload Merchant");
        Payment payment = persistSucceededPayment(merchant, "immutable-payload-payment");
        JsonNode suppliedPayload = objectMapper.readTree("""
                {
                  "payment": {
                    "id": "pay_%s",
                    "status": "SUCCEEDED"
                  }
                }
                """.formatted(payment.getId()));
        JsonNode expectedSnapshot = suppliedPayload.deepCopy();
        WebhookEvent event = new WebhookEvent(
                payment,
                WebhookEventType.PAYMENT_SUCCEEDED,
                suppliedPayload);

        ((ObjectNode) suppliedPayload.path("payment")).put("status", "FAILED");
        WebhookEvent savedEvent = webhookEventRepository.saveAndFlush(event);
        UUID eventId = savedEvent.getId();
        entityManager.clear();

        WebhookEvent reloadedEvent = webhookEventRepository.findById(eventId).orElseThrow();

        assertEquals(expectedSnapshot, reloadedEvent.getPayload());
    }

    @Test
    void rejectsEventWhoseMerchantDoesNotOwnPayment() throws Exception {
        Merchant eventMerchant = persistMerchant("Event Merchant");
        Merchant paymentMerchant = persistMerchant("Payment Merchant");
        Payment payment = persistSucceededPayment(paymentMerchant, "ownership-payment");
        WebhookEvent event = new WebhookEvent(
                payment,
                WebhookEventType.PAYMENT_SUCCEEDED,
                objectMapper.readTree("{\"payment\":{\"status\":\"SUCCEEDED\"}}"));
        ReflectionTestUtils.setField(event, "merchant", eventMerchant);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> webhookEventRepository.saveAndFlush(event));
    }

    @Test
    void rejectsDuplicateEventTypeForSamePayment() throws Exception {
        Merchant merchant = persistMerchant("Duplicate Event Merchant");
        Payment payment = persistSucceededPayment(merchant, "duplicate-event-payment");
        JsonNode payload = objectMapper.readTree("{\"payment\":{\"status\":\"SUCCEEDED\"}}");
        webhookEventRepository.saveAndFlush(
                new WebhookEvent(payment, WebhookEventType.PAYMENT_SUCCEEDED, payload));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> webhookEventRepository.saveAndFlush(
                        new WebhookEvent(
                                payment,
                                WebhookEventType.PAYMENT_SUCCEEDED,
                                payload)));
    }

    @Test
    void allowsSameEventTypeForDifferentPayments() throws Exception {
        Merchant merchant = persistMerchant("Independent Event Merchant");
        Payment firstPayment = persistSucceededPayment(merchant, "first-independent-payment");
        Payment secondPayment = persistSucceededPayment(merchant, "second-independent-payment");
        JsonNode payload = objectMapper.readTree("{\"payment\":{\"status\":\"SUCCEEDED\"}}");

        WebhookEvent firstEvent = webhookEventRepository.saveAndFlush(
                new WebhookEvent(firstPayment, WebhookEventType.PAYMENT_SUCCEEDED, payload));
        WebhookEvent secondEvent = webhookEventRepository.saveAndFlush(
                new WebhookEvent(secondPayment, WebhookEventType.PAYMENT_SUCCEEDED, payload));

        assertNotNull(firstEvent.getId());
        assertNotNull(secondEvent.getId());
    }

    @ParameterizedTest
    @MethodSource("invalidLifecycleStates")
    void rejectsInvalidDeliveryLifecycleState(
            String status,
            int attemptCount,
            Instant lastAttemptAt,
            Instant deliveredAt,
            String lastFailureCode) {
        Merchant merchant = persistMerchant("Lifecycle Event Merchant");
        Payment payment = persistSucceededPayment(merchant, UUID.randomUUID().toString());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertWebhookEvent(
                        merchant.getId(),
                        payment.getId(),
                        status,
                        attemptCount,
                        lastAttemptAt,
                        deliveredAt,
                        lastFailureCode));
    }

    private static Stream<Arguments> invalidLifecycleStates() {
        Instant attemptedAt = Instant.parse("2026-08-18T12:05:00Z");
        Instant deliveredAt = Instant.parse("2026-08-18T12:06:00Z");
        return Stream.of(
                Arguments.of("PENDING", -1, null, null, null),
                Arguments.of("PENDING", 1, null, null, null),
                Arguments.of("PENDING", 0, null, deliveredAt, null),
                Arguments.of("DELIVERED", 1, attemptedAt, null, null),
                Arguments.of("FAILED", 0, null, null, null));
    }

    private static Stream<Arguments> invalidEventTypeSources() {
        return Stream.of(
                Arguments.of(true, "REFUND_SUCCEEDED"),
                Arguments.of(false, "PAYMENT_SUCCEEDED"));
    }

    private Merchant persistMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        return merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
    }

    private Payment persistSucceededPayment(Merchant merchant, String idempotencyKey) {
        MerchantOrder order = persistPaymentPendingOrder(merchant);
        Payment payment = new Payment(order, idempotencyKey);
        payment.markSucceeded(COMPLETED_AT);
        return paymentRepository.saveAndFlush(payment);
    }

    private Payment persistFailedPayment(Merchant merchant, String idempotencyKey) {
        MerchantOrder order = persistPaymentPendingOrder(merchant);
        Payment payment = new Payment(order, idempotencyKey);
        payment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, COMPLETED_AT);
        return paymentRepository.saveAndFlush(payment);
    }

    private Refund persistRefund(Payment payment, String idempotencyKey) {
        return refundRepository.saveAndFlush(new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                idempotencyKey));
    }

    private MerchantOrder persistPaymentPendingOrder(Merchant merchant) {
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        return orderRepository.saveAndFlush(order);
    }

    private void insertWebhookEvent(
            UUID merchantId,
            UUID paymentId,
            String status,
            int attemptCount,
            Instant lastAttemptAt,
            Instant deliveredAt,
            String lastFailureCode) {
        jdbcTemplate.update(
                """
                INSERT INTO webhook_event (
                    id,
                    merchant_id,
                    event_type,
                    payment_id,
                    payload,
                    status,
                    attempt_count,
                    last_attempt_at,
                    delivered_at,
                    last_failure_code
                )
                VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                merchantId,
                "PAYMENT_SUCCEEDED",
                paymentId,
                "{\"payment\":{\"status\":\"SUCCEEDED\"}}",
                status,
                attemptCount,
                lastAttemptAt == null ? null : Timestamp.from(lastAttemptAt),
                deliveredAt == null ? null : Timestamp.from(deliveredAt),
                lastFailureCode);
    }

    private void insertSourcedWebhookEvent(
            UUID merchantId,
            String eventType,
            UUID paymentId,
            UUID refundId) {
        jdbcTemplate.update(
                """
                INSERT INTO webhook_event (
                    id,
                    merchant_id,
                    event_type,
                    payment_id,
                    refund_id,
                    payload
                )
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                UUID.randomUUID(),
                merchantId,
                eventType,
                paymentId,
                refundId,
                "{}");
    }
}
