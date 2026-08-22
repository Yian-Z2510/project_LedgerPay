package com.ledgerpay.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookStatus;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    boolean existsByMerchantIdAndStatus(UUID merchantId, WebhookStatus status);
}
