package com.ledgerpay.exception;

public class InsufficientRefundableAmountException extends RuntimeException {

    private static final String MESSAGE =
            "The requested refund amount exceeds the available refundable amount.";

    public InsufficientRefundableAmountException() {
        super(MESSAGE);
    }
}
