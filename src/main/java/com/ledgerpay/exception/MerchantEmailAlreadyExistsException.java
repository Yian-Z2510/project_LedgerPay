package com.ledgerpay.exception;

public class MerchantEmailAlreadyExistsException extends RuntimeException {

    private static final String MESSAGE = "A merchant with this email already exists.";

    public MerchantEmailAlreadyExistsException() {
        super(MESSAGE);
    }

    public MerchantEmailAlreadyExistsException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
