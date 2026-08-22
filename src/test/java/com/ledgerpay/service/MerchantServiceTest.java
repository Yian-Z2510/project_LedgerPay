package com.ledgerpay.service;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.dto.CreateMerchantResponse;
import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.dto.RotateApiKeyResponse;
import com.ledgerpay.dto.UpdateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.exception.MerchantEmailAlreadyExistsException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    private static final String PLAINTEXT_API_KEY = "lp_test_plaintextApiKey";
    private static final String API_KEY_HASH = "a".repeat(64);

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    private MerchantService merchantService;

    @BeforeEach
    void setUp() {
        merchantService = new MerchantService(
                merchantRepository,
                apiKeyService,
                paymentRepository,
                refundRepository,
                webhookEventRepository);
    }

    @Test
    void createNormalizesName() {
        prepareSuccessfulCreate();

        merchantService.create(createRequest("  Alice   Shop  ", "alice@example.com"));

        Merchant savedMerchant = captureCreatedMerchant();
        assertEquals("Alice   Shop", savedMerchant.getName());
    }

    @Test
    void createNormalizesEmail() {
        prepareSuccessfulCreate();

        merchantService.create(createRequest("Alice Shop", "  Alice@Example.COM  "));

        verify(merchantRepository).existsByEmailIgnoreCase("alice@example.com");
        Merchant savedMerchant = captureCreatedMerchant();
        assertEquals("alice@example.com", savedMerchant.getEmail());
    }

    @Test
    void createPerformsDuplicateEmailPreCheck() {
        when(merchantRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(true);

        assertThrows(
                MerchantEmailAlreadyExistsException.class,
                () -> merchantService.create(createRequest("Alice Shop", "Alice@Example.com")));

        verify(merchantRepository).existsByEmailIgnoreCase("alice@example.com");
        verify(merchantRepository, never()).saveAndFlush(any());
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void createGeneratesOneKeyPersistsItsHashAndReturnsItsPlaintext() {
        prepareSuccessfulCreate();

        CreateMerchantResponse response = merchantService.create(
                createRequest("Alice Shop", "alice@example.com"));

        Merchant savedMerchant = captureCreatedMerchant();
        assertEquals(API_KEY_HASH, savedMerchant.getApiKeyHash());
        assertEquals(PLAINTEXT_API_KEY, response.apiKey());
        verify(apiKeyService, times(1)).generate();
    }

    @Test
    void createMapsEmailConstraintViolationToBusinessException() {
        prepareCreateBeforeSave();
        DataIntegrityViolationException databaseException = integrityViolation(
                "ux_merchant_email_lower");
        when(merchantRepository.saveAndFlush(any(Merchant.class))).thenThrow(databaseException);

        MerchantEmailAlreadyExistsException thrown = assertThrows(
                MerchantEmailAlreadyExistsException.class,
                () -> merchantService.create(createRequest("Alice Shop", "alice@example.com")));

        assertSame(databaseException, thrown.getCause());
    }

    @Test
    void createRethrowsUnrelatedIntegrityViolation() {
        prepareCreateBeforeSave();
        DataIntegrityViolationException databaseException = integrityViolation(
                "merchant_api_key_hash_key");
        when(merchantRepository.saveAndFlush(any(Merchant.class))).thenThrow(databaseException);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantService.create(createRequest("Alice Shop", "alice@example.com")));

        assertSame(databaseException, thrown);
    }

    @Test
    void getCurrentMapsProvidedMerchantWithoutRepositoryLookup() {
        Merchant merchant = persistedMerchant();

        MerchantResponse response = merchantService.getCurrent(merchant);

        assertEquals("mer_" + merchant.getId(), response.id());
        assertEquals(merchant.getName(), response.name());
        assertEquals(merchant.getEmail(), response.email());
        assertEquals(merchant.getStatus(), response.status());
        assertEquals(merchant.getWebhookUrl(), response.webhookUrl());
        verifyNoInteractions(merchantRepository, apiKeyService);
    }

    @Test
    void updateChangesWebhookUrlAndSavesAuthenticatedMerchant() {
        Merchant merchant = persistedMerchant();
        UpdateMerchantRequest request = updateRequest("https://new.example.com/webhooks");
        when(merchantRepository.saveAndFlush(merchant)).thenReturn(merchant);

        MerchantResponse response = merchantService.update(merchant, request);

        assertEquals("https://new.example.com/webhooks", merchant.getWebhookUrl());
        assertEquals("https://new.example.com/webhooks", response.webhookUrl());
        verify(merchantRepository).saveAndFlush(merchant);
    }

    @Test
    void updateSupportsClearingWebhookUrl() {
        Merchant merchant = persistedMerchant();
        UpdateMerchantRequest request = updateRequest(null);
        when(merchantRepository.saveAndFlush(merchant)).thenReturn(merchant);

        MerchantResponse response = merchantService.update(merchant, request);

        assertNull(merchant.getWebhookUrl());
        assertNull(response.webhookUrl());
        verify(merchantRepository).saveAndFlush(merchant);
    }

    @Test
    void rotateApiKeySavesNewHashAndReturnsOnlyPlaintext() {
        Merchant merchant = persistedMerchant();
        GeneratedApiKey generatedApiKey = new GeneratedApiKey(PLAINTEXT_API_KEY, API_KEY_HASH);
        when(apiKeyService.generate()).thenReturn(generatedApiKey);
        when(merchantRepository.save(merchant)).thenReturn(merchant);

        RotateApiKeyResponse response = merchantService.rotateApiKey(merchant);

        assertEquals(API_KEY_HASH, merchant.getApiKeyHash());
        assertEquals(PLAINTEXT_API_KEY, response.apiKey());
        assertEquals(1, RotateApiKeyResponse.class.getRecordComponents().length);
        assertEquals("apiKey", RotateApiKeyResponse.class.getRecordComponents()[0].getName());
        verify(merchantRepository).save(merchant);
    }

    private void prepareSuccessfulCreate() {
        prepareCreateBeforeSave();
        when(merchantRepository.saveAndFlush(any(Merchant.class))).thenAnswer(invocation -> {
            Merchant merchant = invocation.getArgument(0);
            setPersistenceFields(merchant);
            return merchant;
        });
    }

    private void prepareCreateBeforeSave() {
        when(merchantRepository.existsByEmailIgnoreCase(any(String.class))).thenReturn(false);
        when(apiKeyService.generate()).thenReturn(
                new GeneratedApiKey(PLAINTEXT_API_KEY, API_KEY_HASH));
    }

    private Merchant captureCreatedMerchant() {
        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private CreateMerchantRequest createRequest(String name, String email) {
        return new CreateMerchantRequest(
                name,
                email,
                "https://merchant.example.com/webhooks/ledgerpay");
    }

    private UpdateMerchantRequest updateRequest(String webhookUrl) {
        UpdateMerchantRequest request = new UpdateMerchantRequest();
        request.setWebhookUrl(webhookUrl);
        return request;
    }

    private Merchant persistedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", "b".repeat(64));
        merchant.setWebhookUrl("https://merchant.example.com/webhooks/ledgerpay");
        setPersistenceFields(merchant);
        return merchant;
    }

    private void setPersistenceFields(Merchant merchant) {
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(merchant, "createdAt", Instant.parse("2026-08-11T10:00:00Z"));
        ReflectionTestUtils.setField(merchant, "updatedAt", Instant.parse("2026-08-11T10:00:00Z"));
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "could not execute statement",
                new SQLException("constraint violation"),
                constraintName);
        return new DataIntegrityViolationException("database constraint violation", hibernateException);
    }
}
