package com.ledgerpay.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundStatus;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    boolean existsByMerchantIdAndStatus(UUID merchantId, RefundStatus status);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
