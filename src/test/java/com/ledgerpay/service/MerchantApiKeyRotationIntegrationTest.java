package com.ledgerpay.service;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.RotateApiKeyResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.repository.MerchantRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class MerchantApiKeyRotationIntegrationTest {

    private static final String OLD_API_KEY = "lp_test_" + "O".repeat(22);

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void rotationReplacesOldStoredHashWithNewStoredHash() {
        String oldHash = apiKeyService.hashApiKey(OLD_API_KEY);
        Merchant merchant = new Merchant(
                "Rotation Merchant",
                "rotation@example.com",
                oldHash);
        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);

        RotateApiKeyResponse response = merchantService.rotateApiKey(savedMerchant);
        String newHash = apiKeyService.hashApiKey(response.apiKey());
        merchantRepository.flush();
        entityManager.clear();

        Optional<Merchant> merchantWithNewHash = merchantRepository.findByApiKeyHash(newHash);
        assertFalse(merchantRepository.findByApiKeyHash(oldHash).isPresent());
        assertTrue(merchantWithNewHash.isPresent());
        assertEquals(savedMerchant.getId(), merchantWithNewHash.orElseThrow().getId());
    }
}
