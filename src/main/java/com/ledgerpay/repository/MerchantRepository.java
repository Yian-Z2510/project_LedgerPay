package com.ledgerpay.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerpay.entity.Merchant;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByApiKeyHash(String apiKeyHash);

    boolean existsByEmailIgnoreCase(String email);
}
