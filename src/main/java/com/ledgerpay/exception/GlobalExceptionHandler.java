package com.ledgerpay.exception;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ledgerpay.dto.ApiErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String MERCHANT_EMAIL_ALREADY_EXISTS = "MERCHANT_EMAIL_ALREADY_EXISTS";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationFailure(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(
                                FieldError::getDefaultMessage,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(error -> error.getField() + " "
                        + Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"))
                .distinct()
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(VALIDATION_ERROR, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequestBody(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        "Malformed or invalid request body."));
    }

    @ExceptionHandler(MerchantEmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleMerchantEmailAlreadyExists(
            MerchantEmailAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        MERCHANT_EMAIL_ALREADY_EXISTS,
                        "A merchant with this email already exists."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        LOGGER.error("Unexpected error while processing API request.", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        INTERNAL_ERROR,
                        "An unexpected error occurred."));
    }
}
