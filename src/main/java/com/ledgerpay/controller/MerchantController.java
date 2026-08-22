package com.ledgerpay.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.dto.CreateMerchantResponse;
import com.ledgerpay.dto.MerchantResponse;
import com.ledgerpay.dto.RotateApiKeyResponse;
import com.ledgerpay.dto.UpdateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.service.MerchantService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping("/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateMerchantResponse createMerchant(
            @Valid @RequestBody CreateMerchantRequest request) {
        return merchantService.create(request);
    }

    @GetMapping("/merchant")
    public MerchantResponse getCurrentMerchant(
            @AuthenticationPrincipal Merchant merchant) {
        return merchantService.getCurrent(merchant);
    }

    @PatchMapping("/merchant")
    public MerchantResponse updateCurrentMerchant(
            @AuthenticationPrincipal Merchant merchant,
            @Valid @RequestBody UpdateMerchantRequest request) {
        return merchantService.update(merchant, request);
    }

    @PostMapping("/merchant/api-key/rotate")
    public RotateApiKeyResponse rotateApiKey(
            @AuthenticationPrincipal Merchant merchant) {
        return merchantService.rotateApiKey(merchant);
    }

    @PostMapping("/merchant/deactivate")
    public MerchantResponse deactivateMerchant(
            @AuthenticationPrincipal Merchant merchant) {
        return merchantService.deactivate(merchant);
    }
}
