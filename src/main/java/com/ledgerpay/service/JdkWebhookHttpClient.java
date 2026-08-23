package com.ledgerpay.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ledgerpay.dto.WebhookDeliveryRequest;
import com.ledgerpay.entity.WebhookFailureCode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdkWebhookHttpClient implements WebhookHttpClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;

    @Autowired
    public JdkWebhookHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, HTTP_TIMEOUT, Clock.systemUTC());
    }

    JdkWebhookHttpClient(
            ObjectMapper objectMapper,
            Duration timeout,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public WebhookDeliveryResult post(
            String webhookUrl,
            WebhookDeliveryRequest request) {
        HttpRequest httpRequest;
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            httpRequest = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
        } catch (JacksonException | IllegalArgumentException exception) {
            return WebhookDeliveryResult.processingFailed();
        }

        Instant attemptStartedAt = clock.instant();
        try {
            HttpResponse<Void> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return WebhookDeliveryResult.succeeded(
                        attemptStartedAt,
                        clock.instant());
            }
            return WebhookDeliveryResult.requestFailed(
                    attemptStartedAt,
                    WebhookFailureCode.HTTP_ERROR);
        } catch (IOException exception) {
            return WebhookDeliveryResult.requestFailed(
                    attemptStartedAt,
                    WebhookFailureCode.CONNECTION_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Webhook HTTP delivery was interrupted.",
                    exception);
        }
    }
}
