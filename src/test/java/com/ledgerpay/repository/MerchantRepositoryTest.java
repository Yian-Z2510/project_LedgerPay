package com.ledgerpay.repository;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class MerchantRepositoryTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Test
    void savesAndFindsMerchantById() {
        Merchant merchant = createMerchant(
                "Test Merchant",
                "merchant1@example.com",
                '1');

        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);
        Optional<Merchant> retrievedMerchant = merchantRepository.findById(savedMerchant.getId());

        assertTrue(retrievedMerchant.isPresent());
        assertEquals("Test Merchant", retrievedMerchant.get().getName());
        assertEquals("merchant1@example.com", retrievedMerchant.get().getEmail());
        assertEquals("1".repeat(64), retrievedMerchant.get().getApiKeyHash());
        assertEquals(MerchantStatus.ACTIVE, retrievedMerchant.get().getStatus());
    }

    @Test
    void generatesUuidWhenMerchantIsPersisted() {
        Merchant merchant = createMerchant(
                "UUID Merchant",
                "uuid@example.com",
                '2');

        assertNull(merchant.getId());

        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);

        assertNotNull(savedMerchant.getId());
    }

    @Test
    void returnsDatabaseGeneratedTimestampsAfterInsert() {
        Merchant merchant = createMerchant(
                "Timestamp Merchant",
                "timestamps@example.com",
                '3');

        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);

        assertNotNull(savedMerchant.getCreatedAt());
        assertNotNull(savedMerchant.getUpdatedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRefreshesUpdatedAtWithoutChangingCreatedAt() {
        Merchant merchant = createMerchant(
                "Updated Timestamp Merchant",
                "updated-timestamp@example.com",
                'b');
        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);

        try {
            Instant originalCreatedAt = savedMerchant.getCreatedAt();
            Instant originalUpdatedAt = savedMerchant.getUpdatedAt();

            savedMerchant.setName("Updated Timestamp Merchant Name");
            merchantRepository.saveAndFlush(savedMerchant);
            Merchant reloadedMerchant = merchantRepository.findById(savedMerchant.getId())
                    .orElseThrow();

            assertEquals(originalCreatedAt, reloadedMerchant.getCreatedAt());
            assertTrue(reloadedMerchant.getUpdatedAt().isAfter(originalUpdatedAt));
        } finally {
            merchantRepository.deleteById(savedMerchant.getId());
        }
    }

    @Test
    void rejectsEmailsThatDifferOnlyByCase() {
        Merchant firstMerchant = createMerchant(
                "First Merchant",
                "Test@Example.com",
                '4');
        Merchant secondMerchant = createMerchant(
                "Second Merchant",
                "test@example.com",
                '5');

        merchantRepository.saveAndFlush(firstMerchant);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.saveAndFlush(secondMerchant));
    }

    @Test
    void rejectsDuplicateApiKeyHashes() {
        Merchant firstMerchant = createMerchant(
                "First Merchant",
                "api-key-1@example.com",
                '6');

        Merchant secondMerchant = createMerchant(
                "Second Merchant",
                "api-key-2@example.com",
                '6');

        merchantRepository.saveAndFlush(firstMerchant);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.saveAndFlush(secondMerchant));
    }

    @Test
    void rejectsInactiveMerchantWithoutDeactivatedAt() {
        Merchant merchant = createMerchant(
                "Inactive Merchant",
                "inactive@example.com",
                '7');

        merchant.setStatus(MerchantStatus.INACTIVE);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.saveAndFlush(merchant));
    }

    @Test
    void rejectsActiveMerchantWithDeactivatedAt() {
        Merchant merchant = createMerchant(
                "Active Merchant",
                "active-with-deactivation@example.com",
                '8');

        merchant.setDeactivatedAt(Instant.now());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.saveAndFlush(merchant));
    }

    @Test
    void findsMerchantByApiKeyHash() {
        Merchant merchant = createMerchant(
                "API Key Merchant",
                "api-key-lookup@example.com",
                '9');

        Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);
        Optional<Merchant> retrievedMerchant = merchantRepository.findByApiKeyHash("9".repeat(64));

        assertTrue(retrievedMerchant.isPresent());
        assertEquals(savedMerchant.getId(), retrievedMerchant.get().getId());
    }

    @Test
    void findsExistingEmailIgnoringCase() {
        Merchant merchant = createMerchant(
                "Case Merchant",
                "Case@Test.com",
                'a');

        merchantRepository.saveAndFlush(merchant);

        assertTrue(merchantRepository.existsByEmailIgnoreCase("case@test.com"));
    }

    private Merchant createMerchant(String name, String email, char apiKeyHashCharacter) {
        return new Merchant(name, email, String.valueOf(apiKeyHashCharacter).repeat(64));
    }
}
