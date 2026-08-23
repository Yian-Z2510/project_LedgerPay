package com.ledgerpay.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ledgerpay.dto.WebhookDeliveryRequest;
import com.ledgerpay.entity.WebhookFailureCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkWebhookHttpClientTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonEnvelopeWithOnlyStableFieldsAndAcceptsAnyTwoHundredResponse()
            throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            headers.set(exchange.getRequestHeaders());
            body.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        JdkWebhookHttpClient client = client(Duration.ofSeconds(2));

        WebhookDeliveryResult result = client.post(
                serverUrl("/webhook"),
                request());

        assertTrue(result.attempted());
        assertTrue(result.successful());
        assertEquals(NOW, result.attemptStartedAt());
        assertEquals(NOW, result.completedAt());
        assertNull(result.failureCode());
        assertEquals("POST", method.get());
        assertEquals(
                "application/json",
                headers.get().getFirst("Content-Type"));
        JsonNode json = objectMapper.readTree(body.get());
        assertEquals(4, json.size());
        assertEquals("evt_123e4567-e89b-12d3-a456-426614174000", json.path("id").stringValue());
        assertEquals("payment.succeeded", json.path("type").stringValue());
        assertEquals(NOW.toString(), json.path("createdAt").stringValue());
        assertEquals("pay_snapshot", json.path("data").path("payment").path("id").stringValue());
        assertTrue(json.path("status").isMissingNode());
        assertTrue(json.path("attemptCount").isMissingNode());
        assertTrue(json.path("lastAttemptAt").isMissingNode());
        assertTrue(json.path("deliveredAt").isMissingNode());
        assertTrue(json.path("lastFailureCode").isMissingNode());
    }

    @Test
    void doesNotFollowRedirectAndClassifiesItAsHttpError() throws Exception {
        AtomicBoolean redirectTargetReached = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectTargetReached.set(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        WebhookDeliveryResult result = client(Duration.ofSeconds(2)).post(
                serverUrl("/redirect"),
                request());

        assertTrue(result.attempted());
        assertFalse(result.successful());
        assertEquals(WebhookFailureCode.HTTP_ERROR, result.failureCode());
        assertFalse(redirectTargetReached.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void classifiesClientAndServerErrorsAsHttpError(int statusCode) throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });

        WebhookDeliveryResult result = client(Duration.ofSeconds(2)).post(
                serverUrl("/webhook"),
                request());

        assertTrue(result.attempted());
        assertFalse(result.successful());
        assertEquals(WebhookFailureCode.HTTP_ERROR, result.failureCode());
    }

    @Test
    void classifiesRequestTimeoutAsConnectionTimeout() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(300);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        WebhookDeliveryResult result = client(Duration.ofMillis(100)).post(
                serverUrl("/webhook"),
                request());

        assertTrue(result.attempted());
        assertFalse(result.successful());
        assertEquals(WebhookFailureCode.CONNECTION_TIMEOUT, result.failureCode());
    }

    @Test
    void classifiesConnectionFailureAsConnectionTimeout() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        WebhookDeliveryResult result = client(Duration.ofMillis(500)).post(
                "http://127.0.0.1:" + unusedPort + "/webhook",
                request());

        assertTrue(result.attempted());
        assertFalse(result.successful());
        assertEquals(WebhookFailureCode.CONNECTION_TIMEOUT, result.failureCode());
    }

    @Test
    void interruptedSendRestoresFlagAndPropagatesWithoutReturningDeliveryResult()
            throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        JdkWebhookHttpClient client = client(Duration.ofSeconds(2));

        try {
            Thread.currentThread().interrupt();

            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> client.post(serverUrl("/webhook"), request()));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(thrown.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
    }

    private JdkWebhookHttpClient client(Duration timeout) {
        return new JdkWebhookHttpClient(
                objectMapper,
                timeout,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WebhookDeliveryRequest request() throws Exception {
        return new WebhookDeliveryRequest(
                "evt_123e4567-e89b-12d3-a456-426614174000",
                "payment.succeeded",
                NOW,
                objectMapper.readTree("{\"payment\":{\"id\":\"pay_snapshot\"}}"));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> handler.handle(exchange));
        server.start();
    }

    private String serverUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
