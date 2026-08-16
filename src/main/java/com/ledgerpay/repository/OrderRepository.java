package com.ledgerpay.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.ledgerpay.entity.MerchantOrder;

public interface OrderRepository extends JpaRepository<MerchantOrder, UUID> {

    Optional<MerchantOrder> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MerchantOrder> findForUpdateByIdAndMerchantId(UUID id, UUID merchantId);

    List<MerchantOrder> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
}
