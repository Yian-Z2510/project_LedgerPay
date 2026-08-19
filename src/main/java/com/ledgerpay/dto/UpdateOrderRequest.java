package com.ledgerpay.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateOrderRequest(
        @NotNull
        @Positive
        Long amount) {
}
