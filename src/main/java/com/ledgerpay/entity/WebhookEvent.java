package com.ledgerpay.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private WebhookEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", updatable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", updatable = false)
    private Refund refund;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WebhookStatus status = WebhookStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_code", length = 50)
    private WebhookFailureCode lastFailureCode;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(
            Payment payment,
            WebhookEventType eventType,
            JsonNode payload) {
        if (payment == null) {
            throw new IllegalArgumentException("Webhook event Payment must not be null.");
        }

        if (eventType == null) {
            throw new IllegalArgumentException("Webhook event type must not be null.");
        }

        if (eventType != WebhookEventType.PAYMENT_SUCCEEDED
                && eventType != WebhookEventType.PAYMENT_FAILED) {
            throw new IllegalArgumentException(
                    "Payment Webhook event must use a Payment event type.");
        }

        if ((eventType == WebhookEventType.PAYMENT_SUCCEEDED
                        && payment.getStatus() != PaymentStatus.SUCCEEDED)
                || (eventType == WebhookEventType.PAYMENT_FAILED
                        && payment.getStatus() != PaymentStatus.FAILED)) {
            throw new IllegalArgumentException(
                    "Payment Webhook event type must match the Payment status.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("Webhook event payload must not be null.");
        }

        this.payment = payment;
        this.merchant = payment.getMerchant();
        this.eventType = eventType;
        this.payload = payload.deepCopy();
    }

    public WebhookEvent(
            Refund refund,
            WebhookEventType eventType,
            JsonNode payload) {
        if (refund == null) {
            throw new IllegalArgumentException("Webhook event Refund must not be null.");
        }

        if (eventType == null) {
            throw new IllegalArgumentException("Webhook event type must not be null.");
        }

        if (eventType != WebhookEventType.REFUND_SUCCEEDED
                && eventType != WebhookEventType.REFUND_FAILED) {
            throw new IllegalArgumentException(
                    "Refund Webhook event must use a Refund event type.");
        }

        if ((eventType == WebhookEventType.REFUND_SUCCEEDED
                        && refund.getStatus() != RefundStatus.SUCCEEDED)
                || (eventType == WebhookEventType.REFUND_FAILED
                        && refund.getStatus() != RefundStatus.FAILED)) {
            throw new IllegalArgumentException(
                    "Refund Webhook event type must match the Refund status.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("Webhook event payload must not be null.");
        }

        this.refund = refund;
        this.merchant = refund.getMerchant();
        this.eventType = eventType;
        this.payload = payload.deepCopy();
    }

    public UUID getId() {
        return id;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public WebhookEventType getEventType() {
        return eventType;
    }

    public Payment getPayment() {
        return payment;
    }

    public Refund getRefund() {
        return refund;
    }

    public JsonNode getPayload() {
        return payload.deepCopy();
    }

    public WebhookStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public WebhookFailureCode getLastFailureCode() {
        return lastFailureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void recordAutomaticDeliverySucceeded(
            Instant attemptStartedAt,
            Instant deliveredAt) {
        requirePendingStatus();
        requireAttemptStartedAt(attemptStartedAt);
        if (deliveredAt == null || deliveredAt.isBefore(attemptStartedAt)) {
            throw new IllegalArgumentException(
                    "Webhook delivery completion time must not be before its attempt time.");
        }

        this.attemptCount += 1;
        this.lastAttemptAt = attemptStartedAt;
        this.status = WebhookStatus.DELIVERED;
        this.deliveredAt = deliveredAt;
    }

    public void recordAutomaticDeliveryFailed(
            Instant attemptStartedAt,
            WebhookFailureCode failureCode,
            int maximumAttempts) {
        requirePendingStatus();
        requireAttemptStartedAt(attemptStartedAt);
        requireActualDeliveryFailureCode(failureCode);
        if (maximumAttempts <= 0 || attemptCount >= maximumAttempts) {
            throw new IllegalArgumentException("Webhook maximum attempts is invalid.");
        }

        this.attemptCount += 1;
        this.lastAttemptAt = attemptStartedAt;
        this.lastFailureCode = failureCode;
        if (attemptCount >= maximumAttempts) {
            this.status = WebhookStatus.FAILED;
        }
    }

    public void recordProcessingFailure() {
        requirePendingStatus();
        this.status = WebhookStatus.FAILED;
        this.deliveredAt = null;
        this.lastFailureCode = WebhookFailureCode.PROCESSING_ERROR;
    }

    public void recordManualDeliverySucceeded(
            Instant attemptStartedAt,
            Instant deliveredAt) {
        requireFailedStatus();
        requireAttemptStartedAt(attemptStartedAt);
        if (deliveredAt == null || deliveredAt.isBefore(attemptStartedAt)) {
            throw new IllegalArgumentException(
                    "Webhook delivery completion time must not be before its attempt time.");
        }

        this.attemptCount += 1;
        this.lastAttemptAt = attemptStartedAt;
        this.status = WebhookStatus.DELIVERED;
        this.deliveredAt = deliveredAt;
    }

    public void recordManualDeliveryFailed(
            Instant attemptStartedAt,
            WebhookFailureCode failureCode) {
        requireFailedStatus();
        requireAttemptStartedAt(attemptStartedAt);
        requireActualDeliveryFailureCode(failureCode);

        this.attemptCount += 1;
        this.lastAttemptAt = attemptStartedAt;
        this.deliveredAt = null;
        this.lastFailureCode = failureCode;
    }

    public void markWebhookUrlNotConfigured() {
        requirePendingStatus();
        this.status = WebhookStatus.FAILED;
        this.deliveredAt = null;
        this.lastFailureCode = WebhookFailureCode.WEBHOOK_URL_NOT_CONFIGURED;
    }

    private void requirePendingStatus() {
        if (status != WebhookStatus.PENDING) {
            throw new IllegalStateException(
                    "Only a pending Webhook event may record an automatic delivery outcome.");
        }
    }

    private void requireFailedStatus() {
        if (status != WebhookStatus.FAILED) {
            throw new IllegalStateException(
                    "Only a failed Webhook event may record a manual delivery outcome.");
        }
    }

    private void requireAttemptStartedAt(Instant attemptStartedAt) {
        if (attemptStartedAt == null) {
            throw new IllegalArgumentException(
                    "Webhook delivery attempt time must not be null.");
        }
    }

    private void requireActualDeliveryFailureCode(WebhookFailureCode failureCode) {
        if (failureCode != WebhookFailureCode.CONNECTION_TIMEOUT
                && failureCode != WebhookFailureCode.HTTP_ERROR) {
            throw new IllegalArgumentException(
                    "Actual Webhook delivery failure code is invalid.");
        }
    }
}
