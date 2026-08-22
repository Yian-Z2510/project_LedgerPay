package com.ledgerpay.exception;

public class MerchantHasUnfinishedOperationsException extends RuntimeException {

    private static final String MESSAGE =
            "Merchant cannot be deactivated while unfinished operations exist.";

    public MerchantHasUnfinishedOperationsException() {
        super(MESSAGE);
    }
}
