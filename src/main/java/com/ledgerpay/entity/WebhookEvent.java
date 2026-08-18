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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

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

        if (payload == null) {
            throw new IllegalArgumentException("Webhook event payload must not be null.");
        }

        this.payment = payment;
        this.merchant = payment.getMerchant();
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
}
