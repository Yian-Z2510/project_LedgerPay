package com.ledgerpay.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyServiceTest {

    private static final String API_KEY_PATTERN = "^lp_test_[A-Za-z0-9_-]{22}$";
    private static final String LOWERCASE_SHA_256_PATTERN = "^[0-9a-f]{64}$";

    private final ApiKeyService apiKeyService = new ApiKeyService();

    @Test
    void generatedKeyStartsWithV1Prefix() {
        GeneratedApiKey generatedApiKey = apiKeyService.generate();

        assertTrue(generatedApiKey.plaintext().startsWith("lp_test_"));
    }

    @Test
    void generatedKeyHasExpectedFormat() {
        GeneratedApiKey generatedApiKey = apiKeyService.generate();

        assertTrue(generatedApiKey.plaintext().matches(API_KEY_PATTERN));
    }

    @Test
    void generatedKeysAreDifferent() {
        GeneratedApiKey first = apiKeyService.generate();
        GeneratedApiKey second = apiKeyService.generate();

        assertNotEquals(first.plaintext(), second.plaintext());
    }

    @Test
    void generatedHashIsLowercaseSha256Hexadecimal() {
        GeneratedApiKey generatedApiKey = apiKeyService.generate();

        assertTrue(generatedApiKey.hash().matches(LOWERCASE_SHA_256_PATTERN));
    }

    @Test
    void generatedHashMatchesReusableHashOperation() {
        GeneratedApiKey generatedApiKey = apiKeyService.generate();

        assertEquals(
                apiKeyService.hashApiKey(generatedApiKey.plaintext()),
                generatedApiKey.hash());
    }

    @Test
    void samePlaintextAlwaysProducesSameHash() {
        String plaintext = "lp_test_repeatableApiKey";

        assertEquals(
                apiKeyService.hashApiKey(plaintext),
                apiKeyService.hashApiKey(plaintext));
    }

    @Test
    void differentPlaintextsProduceDifferentHashes() {
        String first = "lp_test_firstApiKey";
        String second = "lp_test_secondApiKey";

        assertNotEquals(
                apiKeyService.hashApiKey(first),
                apiKeyService.hashApiKey(second));
    }
}
