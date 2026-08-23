package com.ledgerpay.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookStatus;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Query("""
            SELECT event
            FROM WebhookEvent event
            JOIN FETCH event.merchant
            WHERE event.id = :eventId
            """)
    Optional<WebhookEvent> findForDeliveryById(@Param("eventId") UUID eventId);

    @Query("""
            SELECT event
            FROM WebhookEvent event
            LEFT JOIN event.payment payment
            LEFT JOIN event.refund refund
            LEFT JOIN refund.payment refundPayment
            WHERE event.merchant.id = :merchantId
              AND (payment.id = :paymentId OR refundPayment.id = :paymentId)
            ORDER BY event.createdAt DESC
            """)
    List<WebhookEvent> findPaymentHistory(
            @Param("paymentId") UUID paymentId,
            @Param("merchantId") UUID merchantId);

    boolean existsByMerchantIdAndStatus(UUID merchantId, WebhookStatus status);
}
