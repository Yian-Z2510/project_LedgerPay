package com.ledgerpay.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.dto.CreateMerchantResponse;
import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.MerchantStatus;
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
class BackendLifecyclePostgresAcceptanceTest {

    private static final long PAYMENT_AMOUNT = 1000L;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private WebhookDeliveryService webhookDeliveryService;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<UUID> merchantIds = new ArrayList<>();
    private final List<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();

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
    void fullRefundLifecycleCommitsDurableEventsAndDeliversThemThroughWorker()
            throws Exception {
        startWebhookServer();
        assertEquals(0, pendingWebhookEventCount());

        Merchant merchant = createAndResolveMerchant();
        UUID merchantId = merchant.getId();
        OrderResponse orderResponse = orderService.createOrder(
                merchant,
                new CreateOrderRequest(PAYMENT_AMOUNT));
        UUID orderId = parsePublicId(orderResponse.id(), "ord_");

        MerchantOrder createdOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CREATED, createdOrder.getStatus());

        Payment payment = paymentService.createPayment(
                        merchant,
                        orderId,
                        "backend-lifecycle-payment")
                .payment();
        UUID paymentId = payment.getId();

        Payment pendingPayment = paymentRepository.findById(paymentId).orElseThrow();
        MerchantOrder paymentPendingOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(PaymentStatus.PENDING, pendingPayment.getStatus());
        assertEquals(OrderStatus.PAYMENT_PENDING, paymentPendingOrder.getStatus());

        paymentService.simulatePayment(
                merchant,
                paymentId,
                PaymentSimulationOutcome.SUCCEEDED,
                null);

        Payment succeededPayment = paymentRepository.findById(paymentId).orElseThrow();
        MerchantOrder paidOrder = orderRepository.findById(orderId).orElseThrow();
        WebhookEvent paymentEvent = loadOnlyEvent("payment_id", paymentId);
        UUID paymentEventId = paymentEvent.getId();
        Instant paymentEventCreatedAt = paymentEvent.getCreatedAt();
        JsonNode paymentPayloadSnapshot = paymentEvent.getPayload().deepCopy();

        assertEquals(PaymentStatus.SUCCEEDED, succeededPayment.getStatus());
        assertEquals(OrderStatus.PAID, paidOrder.getStatus());
        assertInitialPendingEvent(paymentEvent, WebhookEventType.PAYMENT_SUCCEEDED);
        assertEquals(0, capturedRequests.size());

        worker().processDueEvents();

        assertEquals(1, capturedRequests.size());
        assertWebhookRequest(
                capturedRequests.getFirst(),
                paymentEventId,
                "payment.succeeded",
                paymentEventCreatedAt,
                paymentPayloadSnapshot);
        WebhookEvent deliveredPaymentEvent = webhookEventRepository.findById(paymentEventId)
                .orElseThrow();
        assertDeliveredEvent(deliveredPaymentEvent, paymentPayloadSnapshot);

        Refund refund = refundService.createRefund(
                        merchant,
                        paymentId,
                        new CreateRefundRequest(PAYMENT_AMOUNT, RefundReasonCode.CUSTOMER_REQUEST),
                        "backend-lifecycle-refund")
                .refund();
        UUID refundId = refund.getId();

        Refund pendingRefund = refundRepository.findById(refundId).orElseThrow();
        Payment refundReservedPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertEquals(RefundStatus.PENDING, pendingRefund.getStatus());
        assertEquals(PAYMENT_AMOUNT, refundReservedPayment.getPendingRefundAmount());
        assertEquals(0L, refundReservedPayment.getRefundedAmount());

        refundService.simulateRefund(
                merchant,
                refundId,
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund succeededRefund = refundRepository.findById(refundId).orElseThrow();
        Payment fullyRefundedPayment = paymentRepository.findById(paymentId).orElseThrow();
        MerchantOrder refundedOrder = orderRepository.findById(orderId).orElseThrow();
        WebhookEvent refundEvent = loadOnlyEvent("refund_id", refundId);
        UUID refundEventId = refundEvent.getId();
        Instant refundEventCreatedAt = refundEvent.getCreatedAt();
        JsonNode refundPayloadSnapshot = refundEvent.getPayload().deepCopy();

        assertEquals(RefundStatus.SUCCEEDED, succeededRefund.getStatus());
        assertNull(succeededRefund.getFailureCode());
        assertEquals(PaymentStatus.SUCCEEDED, fullyRefundedPayment.getStatus());
        assertEquals(PAYMENT_AMOUNT, fullyRefundedPayment.getRefundedAmount());
        assertEquals(0L, fullyRefundedPayment.getPendingRefundAmount());
        assertEquals(OrderStatus.REFUNDED, refundedOrder.getStatus());
        assertInitialPendingEvent(refundEvent, WebhookEventType.REFUND_SUCCEEDED);
        assertEquals(1, capturedRequests.size());

        worker().processDueEvents();

        assertEquals(2, capturedRequests.size());
        assertWebhookRequest(
                capturedRequests.get(1),
                refundEventId,
                "refund.succeeded",
                refundEventCreatedAt,
                refundPayloadSnapshot);

        Merchant finalMerchant = merchantRepository.findById(merchantId).orElseThrow();
        MerchantOrder finalOrder = orderRepository.findById(orderId).orElseThrow();
        Payment finalPayment = paymentRepository.findById(paymentId).orElseThrow();
        Refund finalRefund = refundRepository.findById(refundId).orElseThrow();
        WebhookEvent finalPaymentEvent = webhookEventRepository.findById(paymentEventId)
                .orElseThrow();
        WebhookEvent finalRefundEvent = webhookEventRepository.findById(refundEventId)
                .orElseThrow();

        assertEquals(MerchantStatus.ACTIVE, finalMerchant.getStatus());
        assertEquals(OrderStatus.REFUNDED, finalOrder.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, finalPayment.getStatus());
        assertEquals(PAYMENT_AMOUNT, finalPayment.getRefundedAmount());
        assertEquals(0L, finalPayment.getPendingRefundAmount());
        assertEquals(RefundStatus.SUCCEEDED, finalRefund.getStatus());
        assertEquals(WebhookEventType.PAYMENT_SUCCEEDED, finalPaymentEvent.getEventType());
        assertDeliveredEvent(finalPaymentEvent, paymentPayloadSnapshot);
        assertEquals(WebhookEventType.REFUND_SUCCEEDED, finalRefundEvent.getEventType());
        assertDeliveredEvent(finalRefundEvent, refundPayloadSnapshot);
    }

    private Merchant createAndResolveMerchant() {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        CreateMerchantResponse response = merchantService.create(new CreateMerchantRequest(
                "Backend Lifecycle Acceptance",
                uniqueValue + "@example.com",
                serverUrl()));
        UUID merchantId = parsePublicId(response.id(), "mer_");
        merchantIds.add(merchantId);

        Merchant merchant = merchantRepository.findByApiKeyHash(
                        apiKeyService.hashApiKey(response.apiKey()))
                .orElseThrow();
        assertEquals(merchantId, merchant.getId());
        assertEquals(MerchantStatus.ACTIVE, merchant.getStatus());
        assertEquals(serverUrl(), merchant.getWebhookUrl());
        return merchant;
    }

    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(
                webhookEventRepository,
                webhookDeliveryService,
                Clock.systemUTC());
    }

    private WebhookEvent loadOnlyEvent(String sourceColumn, UUID sourceId) {
        List<UUID> eventIds = jdbcTemplate.query(
                "SELECT id FROM webhook_event WHERE " + sourceColumn + " = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                sourceId);
        assertEquals(1, eventIds.size());
        return webhookEventRepository.findById(eventIds.getFirst()).orElseThrow();
    }

    private int pendingWebhookEventCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_event WHERE status = 'PENDING'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void assertInitialPendingEvent(
            WebhookEvent event,
            WebhookEventType expectedType) {
        assertEquals(expectedType, event.getEventType());
        assertEquals(WebhookStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getLastAttemptAt());
        assertNull(event.getDeliveredAt());
        assertNull(event.getLastFailureCode());
    }

    private void assertDeliveredEvent(WebhookEvent event, JsonNode expectedPayload) {
        assertEquals(WebhookStatus.DELIVERED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getLastAttemptAt());
        assertNotNull(event.getDeliveredAt());
        assertEquals(expectedPayload, event.getPayload());
    }

    private void assertWebhookRequest(
            CapturedRequest request,
            UUID eventId,
            String eventType,
            Instant createdAt,
            JsonNode persistedPayload) throws Exception {
        assertEquals("POST", request.method());
        assertEquals("application/json", request.contentType());
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals("evt_" + eventId, body.path("id").stringValue());
        assertEquals(eventType, body.path("type").stringValue());
        assertEquals(createdAt.toString(), body.path("createdAt").stringValue());
        assertEquals(persistedPayload, body.path("data"));
    }

    private UUID parsePublicId(String publicId, String prefix) {
        return UUID.fromString(publicId.substring(prefix.length()));
    }

    private void startWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", this::captureWebhook);
        server.start();
    }

    private void captureWebhook(HttpExchange exchange) throws IOException {
        capturedRequests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    private record CapturedRequest(String method, String contentType, String body) {
    }
}
