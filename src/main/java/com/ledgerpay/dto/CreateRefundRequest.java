package com.ledgerpay.dto;

import com.ledgerpay.entity.RefundReasonCode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateRefundRequest(
        @NotNull
        @Positive
        Long amount,
        @NotNull
        RefundReasonCode reasonCode) {
}
