package com.ledgerpay.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePaymentRequest(
        @NotBlank
        String orderId) {
}
