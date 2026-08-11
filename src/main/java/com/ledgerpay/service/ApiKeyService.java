package com.ledgerpay.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class ApiKeyService {

    private static final String API_KEY_PREFIX = "lp_test_";
    private static final int RANDOM_BYTE_COUNT = 16;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedApiKey generate() {
        byte[] randomBytes = new byte[RANDOM_BYTE_COUNT];
        secureRandom.nextBytes(randomBytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
        String plaintext = API_KEY_PREFIX + secret;

        return new GeneratedApiKey(plaintext, hashApiKey(plaintext));
    }

    public String hashApiKey(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
