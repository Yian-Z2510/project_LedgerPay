package com.ledgerpay.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    boolean existsByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    boolean existsByMerchantIdAndStatus(UUID merchantId, PaymentStatus status);

    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    boolean existsByOrderIdAndStatus(UUID orderId, PaymentStatus status);
}
