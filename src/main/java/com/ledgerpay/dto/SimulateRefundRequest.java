package com.ledgerpay.dto;

import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundSimulationOutcome;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record SimulateRefundRequest(
        @NotNull
        RefundSimulationOutcome outcome,
        RefundFailureCode failureCode) {

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
