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
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_order_id", nullable = false, updatable = false)
    private MerchantOrder order;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private OrderCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "refunded_amount", nullable = false)
    private Long refundedAmount = 0L;

    @Column(name = "pending_refund_amount", nullable = false)
    private Long pendingRefundAmount = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 50)
    private PaymentFailureCode failureCode;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(MerchantOrder order, String idempotencyKey) {
        if (order == null) {
            throw new IllegalArgumentException("Payment order must not be null.");
        }

        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Payment idempotency key must contain between 1 and 100 characters.");
        }

        this.order = order;
        this.merchant = order.getMerchant();
        this.amount = order.getAmount();
        this.currency = order.getCurrency();
        this.idempotencyKey = idempotencyKey;
    }

    public void markSucceeded(Instant completedAt) {
        requirePendingStatus();

        if (completedAt == null) {
            throw new IllegalArgumentException("Payment completion time must not be null.");
        }

        this.status = PaymentStatus.SUCCEEDED;
        this.failureCode = null;
        this.completedAt = completedAt;
    }

    public void markFailed(PaymentFailureCode failureCode, Instant completedAt) {
        requirePendingStatus();

        if (failureCode == null) {
            throw new IllegalArgumentException("Payment failure code must not be null.");
        }

        if (completedAt == null) {
            throw new IllegalArgumentException("Payment completion time must not be null.");
        }

        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.completedAt = completedAt;
    }

    public void reserveRefundAmount(Long refundAmount) {
        if (refundAmount == null || refundAmount <= 0) {
            throw new IllegalArgumentException(
                    "Reserved Refund amount must be greater than zero.");
        }

        this.pendingRefundAmount += refundAmount;
    }

    private void requirePendingStatus() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Only a pending Payment may transition.");
        }
    }

    public UUID getId() {
        return id;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public MerchantOrder getOrder() {
        return order;
    }

    public Long getAmount() {
        return amount;
    }

    public OrderCurrency getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getRefundedAmount() {
        return refundedAmount;
    }

    public Long getPendingRefundAmount() {
        return pendingRefundAmount;
    }

    public PaymentFailureCode getFailureCode() {
        return failureCode;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
