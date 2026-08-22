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
import org.hibernate.generator.EventType;

@Entity
@Table(name = "refund")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private OrderCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RefundStatus status = RefundStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, updatable = false, length = 50)
    private RefundReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private RefundFailureCode failureCode;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Refund() {
    }

    public Refund(
            Payment payment,
            Long amount,
            RefundReasonCode reasonCode,
            String idempotencyKey) {
        if (payment == null) {
            throw new IllegalArgumentException("Refund Payment must not be null.");
        }

        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero.");
        }

        if (reasonCode == null) {
            throw new IllegalArgumentException("Refund reason code must not be null.");
        }

        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Refund idempotency key must contain between 1 and 100 characters.");
        }

        this.payment = payment;
        this.merchant = payment.getMerchant();
        this.amount = amount;
        this.currency = payment.getCurrency();
        this.reasonCode = reasonCode;
        this.idempotencyKey = idempotencyKey;
    }

    public void markSucceeded() {
        requirePendingStatus();
        this.status = RefundStatus.SUCCEEDED;
        this.failureCode = null;
    }

    public void markFailed(RefundFailureCode failureCode) {
        requirePendingStatus();

        if (failureCode == null) {
            throw new IllegalArgumentException("Refund failure code must not be null.");
        }

        this.status = RefundStatus.FAILED;
        this.failureCode = failureCode;
    }

    private void requirePendingStatus() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Only a pending Refund may transition.");
        }
    }

    public UUID getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public Long getAmount() {
        return amount;
    }

    public OrderCurrency getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public RefundReasonCode getReasonCode() {
        return reasonCode;
    }

    public RefundFailureCode getFailureCode() {
        return failureCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
