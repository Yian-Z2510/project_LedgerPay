package com.ledgerpay.controller;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.dto.CreateMerchantResponse;
import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.dto.RotateApiKeyResponse;
import com.ledgerpay.dto.UpdateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantStatus;
import com.ledgerpay.exception.GlobalExceptionHandler;
import com.ledgerpay.exception.MerchantHasUnfinishedOperationsException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;
import com.ledgerpay.service.MerchantService;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MerchantController.class)
@Import({
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
class MerchantControllerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-11T11:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MerchantService merchantService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void createMerchantReturnsCreatedResponseIncludingOneTimeApiKey() throws Exception {
        CreateMerchantResponse response = new CreateMerchantResponse(
                "mer_123",
                "Alice Shop",
                "alice@example.com",
                MerchantStatus.ACTIVE,
                "https://merchant.example.com/webhooks/ledgerpay",
                null,
                CREATED_AT,
                UPDATED_AT,
                API_KEY);
        when(merchantService.create(any(CreateMerchantRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Alice Shop",
                                  "email":"alice@example.com",
                                  "webhookUrl":"https://merchant.example.com/webhooks/ledgerpay"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("mer_123"))
                .andExpect(jsonPath("$.apiKey").value(API_KEY))
                .andExpect(jsonPath("$.apiKeyHash").doesNotExist());

        verify(merchantService).create(new CreateMerchantRequest(
                "Alice Shop",
                "alice@example.com",
                "https://merchant.example.com/webhooks/ledgerpay"));
    }

    @Test
    void invalidCreateRequestReturnsInvalidRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"alice@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("name must not be blank"));

        verifyNoInteractions(merchantService);
    }

    @Test
    void getCurrentMerchantPassesAuthenticatedPrincipalDirectlyToService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantResponse response = merchantResponse(merchant, merchant.getWebhookUrl());
        authenticate(merchant);
        when(merchantService.getCurrent(merchant)).thenReturn(response);

        mockMvc.perform(get("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mer_" + merchant.getId()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKeyHash").doesNotExist());

        verify(merchantService).getCurrent(merchant);
    }

    @Test
    void protectedMerchantEndpointWithoutCredentialsReturnsGenericUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/merchant"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid or missing API credentials."));

        verifyNoInteractions(merchantService);
    }

    @Test
    void updateCurrentMerchantSupportsExplicitNullWebhookUrl() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(merchantService.update(any(Merchant.class), any(UpdateMerchantRequest.class)))
                .thenReturn(merchantResponse(merchant, null));

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"webhookUrl":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value((Object) null))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKeyHash").doesNotExist());

        ArgumentCaptor<UpdateMerchantRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateMerchantRequest.class);
        verify(merchantService).update(same(merchant), requestCaptor.capture());
        assertTrue(requestCaptor.getValue().hasWebhookUrl());
        assertNull(requestCaptor.getValue().getWebhookUrl());
    }

    @Test
    void emptyUpdateRequestIsRejectedWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "webhookUrlPresent webhookUrl must be provided"));

        verify(merchantService, never()).update(any(), any());
    }

    @Test
    void rotateApiKeyPassesAuthenticatedMerchantAndReturnsOnlyNewPlaintextKey()
            throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(merchantService.rotateApiKey(merchant)).thenReturn(new RotateApiKeyResponse(API_KEY));

        mockMvc.perform(post("/api/v1/merchant/api-key/rotate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"apiKey\":\"" + API_KEY + "\"}"))
                .andExpect(jsonPath("$.apiKeyHash").doesNotExist());

        verify(merchantService).rotateApiKey(merchant);
    }

    @Test
    void deactivateMerchantReturnsUpdatedMerchantWithoutLocationHeader() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Instant deactivatedAt = Instant.parse("2026-08-22T12:00:00Z");
        MerchantResponse response = new MerchantResponse(
                "mer_" + merchant.getId(),
                merchant.getName(),
                merchant.getEmail(),
                MerchantStatus.INACTIVE,
                merchant.getWebhookUrl(),
                deactivatedAt,
                merchant.getCreatedAt(),
                UPDATED_AT);
        authenticate(merchant);
        when(merchantService.deactivate(merchant)).thenReturn(response);

        mockMvc.perform(post("/api/v1/merchant/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value("mer_" + merchant.getId()))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.deactivatedAt").value(deactivatedAt.toString()))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKeyHash").doesNotExist());

        verify(merchantService).deactivate(merchant);
    }

    @Test
    void deactivateMerchantWithUnfinishedOperationsReturnsConflictContract() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(merchantService.deactivate(merchant))
                .thenThrow(new MerchantHasUnfinishedOperationsException());

        mockMvc.perform(post("/api/v1/merchant/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_HAS_UNFINISHED_OPERATIONS"))
                .andExpect(jsonPath("$.message").value(
                        "Merchant cannot be deactivated while unfinished operations exist."));
    }

    @Test
    void clientCannotSupplyMerchantIdForCurrentMerchantUpdate() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantId":"mer_client_controlled",
                                  "webhookUrl":"https://example.com/webhooks"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Malformed or invalid request body."));

        verify(merchantService, never()).update(any(), any());
    }

    private void authenticate(Merchant merchant) {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
    }

    private Merchant authenticatedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", API_KEY_HASH);
        merchant.setWebhookUrl("https://merchant.example.com/webhooks/ledgerpay");
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(merchant, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(merchant, "updatedAt", UPDATED_AT);
        return merchant;
    }

    private MerchantResponse merchantResponse(Merchant merchant, String webhookUrl) {
        return new MerchantResponse(
                "mer_" + merchant.getId(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                webhookUrl,
                merchant.getDeactivatedAt(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}
