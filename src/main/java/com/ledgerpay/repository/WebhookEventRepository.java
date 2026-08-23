package com.ledgerpay.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
            JOIN FETCH event.merchant
            WHERE event.id = :eventId
              AND event.merchant.id = :merchantId
            """)
    Optional<WebhookEvent> findForDeliveryByIdAndMerchantId(
            @Param("eventId") UUID eventId,
            @Param("merchantId") UUID merchantId);

    @Query("""
            SELECT event.id
            FROM WebhookEvent event
            WHERE event.status = :status
              AND event.attemptCount < :maximumAttempts
              AND (
                  event.attemptCount = 0
                  OR event.lastAttemptAt <= :retryCutoff
              )
            ORDER BY event.createdAt ASC
            """)
    List<UUID> findDueEventIds(
            @Param("status") WebhookStatus status,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("retryCutoff") Instant retryCutoff,
            Pageable pageable);

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
