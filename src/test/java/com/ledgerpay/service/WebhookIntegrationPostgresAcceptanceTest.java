package com.ledgerpay.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundSimulationOutcome;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class WebhookIntegrationPostgresAcceptanceTest {

    private static final long PAYMENT_AMOUNT = 1000L;
    private static final long REFUND_AMOUNT = 300L;

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
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private WebhookDeliveryService webhookDeliveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<UUID> merchantIds = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();

    private HttpServer server;

    @AfterEach
    void cleanUp() {
        if (server != null) {
            server.stop(0);
        }

        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM refund WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void paymentCommitCreatesDurableEventThatWorkerDeliversOverRealHttp()
            throws Exception {
        startWebhookServer();
        Merchant merchant = createMerchant("Payment Webhook Integration");
        MerchantOrder order = orderRepository.saveAndFlush(
                new MerchantOrder(merchant, PAYMENT_AMOUNT));
        PaymentCreationResult creation = paymentService.createPayment(
                merchant,
                order.getId(),
                "payment-webhook-integration-key");

        paymentService.simulatePayment(
                merchant,
                creation.payment().getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        Payment committedPayment = paymentRepository.findById(
                        creation.payment().getId())
                .orElseThrow();
        MerchantOrder committedOrder = orderRepository.findById(order.getId()).orElseThrow();
        WebhookEvent pendingEvent = loadOnlyPaymentEvent(committedPayment.getId());
        JsonNode persistedPayload = pendingEvent.getPayload();
        Instant persistedCreatedAt = pendingEvent.getCreatedAt();
        UUID eventId = pendingEvent.getId();

        assertEquals(PaymentStatus.SUCCEEDED, committedPayment.getStatus());
        assertEquals(OrderStatus.PAID, committedOrder.getStatus());
        assertPendingInitialState(pendingEvent, WebhookEventType.PAYMENT_SUCCEEDED);
        assertEquals(0, requestCount.get());

        worker().processDueEvents();

        assertDeliveredRequest(
                eventId,
                "payment.succeeded",
                persistedCreatedAt,
                persistedPayload);
        WebhookEvent deliveredEvent = webhookEventRepository.findById(eventId).orElseThrow();
        assertDeliveredState(deliveredEvent, persistedPayload);
    }

    @Test
    void refundCommitCreatesDurableEventThatWorkerDeliversOverRealHttp()
            throws Exception {
        startWebhookServer();
        Merchant merchant = createMerchant("Refund Webhook Integration");
        Payment payment = createSucceededPaymentFixture(merchant);
        Refund refund = refundService.createRefund(
                        merchant,
                        payment.getId(),
                        new CreateRefundRequest(
                                REFUND_AMOUNT,
                                RefundReasonCode.CUSTOMER_REQUEST),
                        "refund-webhook-integration-key")
                .refund();

        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund committedRefund = refundRepository.findById(refund.getId()).orElseThrow();
        Payment committedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        MerchantOrder committedOrder = orderRepository.findById(
                        payment.getOrder().getId())
                .orElseThrow();
        WebhookEvent pendingEvent = loadOnlyRefundEvent(committedRefund.getId());
        JsonNode persistedPayload = pendingEvent.getPayload();
        Instant persistedCreatedAt = pendingEvent.getCreatedAt();
        UUID eventId = pendingEvent.getId();

        assertEquals(RefundStatus.SUCCEEDED, committedRefund.getStatus());
        assertEquals(0L, committedPayment.getPendingRefundAmount());
        assertEquals(REFUND_AMOUNT, committedPayment.getRefundedAmount());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, committedOrder.getStatus());
        assertPendingInitialState(pendingEvent, WebhookEventType.REFUND_SUCCEEDED);
        assertEquals(0, requestCount.get());

        worker().processDueEvents();

        assertDeliveredRequest(
                eventId,
                "refund.succeeded",
                persistedCreatedAt,
                persistedPayload);
        WebhookEvent deliveredEvent = webhookEventRepository.findById(eventId).orElseThrow();
        assertDeliveredState(deliveredEvent, persistedPayload);
    }

    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(
                webhookEventRepository,
                webhookDeliveryService,
                Clock.systemUTC());
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2));
        merchant.setWebhookUrl(serverUrl());
        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);
        merchantIds.add(savedMerchant.getId());
        return savedMerchant;
    }

    private Payment createSucceededPaymentFixture(Merchant merchant) {
        MerchantOrder order = new MerchantOrder(merchant, PAYMENT_AMOUNT);
        order.setStatus(OrderStatus.PAID);
        MerchantOrder savedOrder = orderRepository.saveAndFlush(order);
        Payment payment = new Payment(
                savedOrder,
                "succeeded-payment-fixture-" + UUID.randomUUID());
        payment.markSucceeded(Instant.now());
        return paymentRepository.saveAndFlush(payment);
    }

    private WebhookEvent loadOnlyPaymentEvent(UUID paymentId) {
        return loadOnlyEvent("payment_id", paymentId);
    }

    private WebhookEvent loadOnlyRefundEvent(UUID refundId) {
        return loadOnlyEvent("refund_id", refundId);
    }

    private WebhookEvent loadOnlyEvent(String sourceColumn, UUID sourceId) {
        List<UUID> eventIds = jdbcTemplate.query(
                "SELECT id FROM webhook_event WHERE " + sourceColumn + " = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                sourceId);
        assertEquals(1, eventIds.size());
        return webhookEventRepository.findById(eventIds.getFirst()).orElseThrow();
    }

    private void assertPendingInitialState(
            WebhookEvent event,
            WebhookEventType expectedType) {
        assertEquals(expectedType, event.getEventType());
        assertEquals(WebhookStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getLastAttemptAt());
        assertNull(event.getDeliveredAt());
        assertNull(event.getLastFailureCode());
    }

    private void assertDeliveredRequest(
            UUID eventId,
            String eventType,
            Instant createdAt,
            JsonNode persistedPayload) throws Exception {
        assertEquals(1, requestCount.get());
        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals("evt_" + eventId, body.path("id").stringValue());
        assertEquals(eventType, body.path("type").stringValue());
        assertEquals(createdAt.toString(), body.path("createdAt").stringValue());
        assertEquals(persistedPayload, body.path("data"));
    }

    private void assertDeliveredState(
            WebhookEvent event,
            JsonNode persistedPayload) {
        assertEquals(WebhookStatus.DELIVERED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getLastAttemptAt());
        assertNotNull(event.getDeliveredAt());
        assertEquals(persistedPayload, event.getPayload());
    }

    private void startWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", this::captureWebhook);
        server.start();
    }

    private void captureWebhook(HttpExchange exchange) throws IOException {
        capturedRequest.set(new CapturedRequest(
                exchange.getRequestMethod(),
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)));
        requestCount.incrementAndGet();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    private record CapturedRequest(String method, String body) {
    }
}
