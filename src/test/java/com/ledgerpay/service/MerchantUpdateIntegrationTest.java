package com.ledgerpay.service;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.dto.UpdateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.repository.MerchantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MerchantUpdateIntegrationTest {

    private static final String UPDATED_WEBHOOK_URL = "https://updated.example.com/webhooks";

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updateReturnsDatabaseGeneratedTimestampAndPersistsUpdatedState() {
        String uniqueValue = UUID.randomUUID().toString();
        Merchant merchant = new Merchant(
                "Update Integration Merchant",
                uniqueValue + "@example.com",
                apiKeyService.hashApiKey("lp_test_" + uniqueValue));
        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);

        try {
            Instant originalCreatedAt = savedMerchant.getCreatedAt();
            Instant originalUpdatedAt = savedMerchant.getUpdatedAt();
            UpdateMerchantRequest request = new UpdateMerchantRequest();
            request.setWebhookUrl(UPDATED_WEBHOOK_URL);

            MerchantResponse response = merchantService.update(savedMerchant, request);

            assertEquals(originalCreatedAt, response.createdAt());
            assertTrue(response.updatedAt().isAfter(originalUpdatedAt));
            assertEquals(UPDATED_WEBHOOK_URL, response.webhookUrl());

            Merchant persistedMerchant = merchantRepository.findById(savedMerchant.getId())
                    .orElseThrow();
            assertEquals(originalCreatedAt, persistedMerchant.getCreatedAt());
            assertEquals(response.updatedAt(), persistedMerchant.getUpdatedAt());
            assertEquals(UPDATED_WEBHOOK_URL, persistedMerchant.getWebhookUrl());
        } finally {
            merchantRepository.deleteById(savedMerchant.getId());
        }
    }
}
