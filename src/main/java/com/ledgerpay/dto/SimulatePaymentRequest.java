package com.ledgerpay.dto;

import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record SimulatePaymentRequest(
        @NotNull
        PaymentSimulationOutcome outcome,
        PaymentFailureCode failureCode) {

    @AssertTrue(message = "must be null for SUCCEEDED and non-null for FAILED")
    public boolean isFailureCodeValid() {
        if (outcome == null) {
            return true;
        }

        return switch (outcome) {
            case SUCCEEDED -> failureCode == null;
            case FAILED -> failureCode != null;
        };
    }
}
