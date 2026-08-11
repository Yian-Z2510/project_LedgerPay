package com.ledgerpay.service;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.dto.CreateMerchantResponse;
import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.dto.RotateApiKeyResponse;
import com.ledgerpay.dto.UpdateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.exception.MerchantEmailAlreadyExistsException;
import com.ledgerpay.repository.MerchantRepository;

@Service
public class MerchantService {

    private static final String MERCHANT_ID_PREFIX = "mer_";
    private static final String EMAIL_UNIQUE_CONSTRAINT = "ux_merchant_email_lower";

    private final MerchantRepository merchantRepository;
    private final ApiKeyService apiKeyService;

    public MerchantService(MerchantRepository merchantRepository, ApiKeyService apiKeyService) {
        this.merchantRepository = merchantRepository;
        this.apiKeyService = apiKeyService;
    }

    @Transactional
    public CreateMerchantResponse create(CreateMerchantRequest request) {
        String normalizedName = request.name().trim();
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);

        if (merchantRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new MerchantEmailAlreadyExistsException();
        }

        GeneratedApiKey generatedApiKey = apiKeyService.generate();
        Merchant merchant = new Merchant(normalizedName, normalizedEmail, generatedApiKey.hash());
        merchant.setWebhookUrl(request.webhookUrl());

        try {
            Merchant savedMerchant = merchantRepository.saveAndFlush(merchant);
            return toCreateMerchantResponse(savedMerchant, generatedApiKey.plaintext());
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                throw new MerchantEmailAlreadyExistsException(exception);
            }
            throw exception;
        }
    }

    public MerchantResponse getCurrent(Merchant authenticatedMerchant) {
        return toMerchantResponse(authenticatedMerchant);
    }

    @Transactional
    public MerchantResponse update(
            Merchant authenticatedMerchant,
            UpdateMerchantRequest request) {
        authenticatedMerchant.setWebhookUrl(request.getWebhookUrl());
        Merchant savedMerchant = merchantRepository.saveAndFlush(authenticatedMerchant);
        return toMerchantResponse(savedMerchant);
    }

    @Transactional
    public RotateApiKeyResponse rotateApiKey(Merchant authenticatedMerchant) {
        GeneratedApiKey generatedApiKey = apiKeyService.generate();
        authenticatedMerchant.setApiKeyHash(generatedApiKey.hash());
        merchantRepository.save(authenticatedMerchant);
        return new RotateApiKeyResponse(generatedApiKey.plaintext());
    }

    private boolean isEmailUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && EMAIL_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private CreateMerchantResponse toCreateMerchantResponse(
            Merchant merchant,
            String plaintextApiKey) {
        return new CreateMerchantResponse(
                MERCHANT_ID_PREFIX + merchant.getId(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                merchant.getWebhookUrl(),
                merchant.getDeactivatedAt(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt(),
                plaintextApiKey);
    }

    private MerchantResponse toMerchantResponse(Merchant merchant) {
        return new MerchantResponse(
                MERCHANT_ID_PREFIX + merchant.getId(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                merchant.getWebhookUrl(),
                merchant.getDeactivatedAt(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}
