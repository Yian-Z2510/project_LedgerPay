package com.ledgerpay.dto;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void acceptsValidCreateMerchantRequest() {
        CreateMerchantRequest request = new CreateMerchantRequest(
                "Alice Shop",
                "alice@example.com",
                "https://merchant.example.com/webhooks/ledgerpay");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsInvalidCreateMerchantRequestFields() {
        CreateMerchantRequest request = new CreateMerchantRequest(
                " ",
                " ",
                "ftp://merchant.example.com/webhooks");

        Set<ConstraintViolation<CreateMerchantRequest>> violations = validator.validate(request);

        assertEquals(3, violations.size());
    }

    @Test
    void updateRequestDistinguishesMissingWebhookUrlFromExplicitNull() throws Exception {
        UpdateMerchantRequest missing = objectMapper.readValue("{}", UpdateMerchantRequest.class);
        UpdateMerchantRequest explicitNull = objectMapper.readValue(
                "{\"webhookUrl\":null}",
                UpdateMerchantRequest.class);

        assertFalse(missing.hasWebhookUrl());
        assertFalse(validator.validate(missing).isEmpty());
        assertTrue(explicitNull.hasWebhookUrl());
        assertNull(explicitNull.getWebhookUrl());
        assertTrue(validator.validate(explicitNull).isEmpty());
    }

    @Test
    void acceptsHttpAndHttpsWebhookUrls() {
        UpdateMerchantRequest httpRequest = updateRequest("http://merchant.example.com/webhooks");
        UpdateMerchantRequest httpsRequest = updateRequest("https://merchant.example.com/webhooks");

        assertTrue(validator.validate(httpRequest).isEmpty());
        assertTrue(validator.validate(httpsRequest).isEmpty());
    }

    @Test
    void rejectsBlankMalformedAndUnsupportedWebhookUrls() {
        assertFalse(validator.validate(updateRequest(" ")).isEmpty());
        assertFalse(validator.validate(updateRequest("https://bad host/webhooks")).isEmpty());
        assertFalse(validator.validate(updateRequest("ftp://merchant.example.com/webhooks")).isEmpty());
    }

    @Test
    void rejectsWebhookUrlLongerThan2048Characters() {
        String value = "https://merchant.example.com/" + "a".repeat(2049);

        assertFalse(validator.validate(updateRequest(value)).isEmpty());
    }

    @Test
    void rejectsUnknownCreateMerchantRequestFields() {
        String json = """
                {
                  "name": "Alice Shop",
                  "email": "alice@example.com",
                  "merchantId": "mer_not-client-controlled"
                }
                """;

        assertThrows(Exception.class, () -> objectMapper.readValue(json, CreateMerchantRequest.class));
    }

    @Test
    void rejectsInternalPresenceFlagAsAnUnknownJsonField() {
        String json = """
                {
                  "webhookUrl": null,
                  "webhookUrlPresent": true
                }
                """;

        assertThrows(Exception.class, () -> objectMapper.readValue(json, UpdateMerchantRequest.class));
    }

    private UpdateMerchantRequest updateRequest(String webhookUrl) {
        UpdateMerchantRequest request = new UpdateMerchantRequest();
        request.setWebhookUrl(webhookUrl);
        return request;
    }
}
