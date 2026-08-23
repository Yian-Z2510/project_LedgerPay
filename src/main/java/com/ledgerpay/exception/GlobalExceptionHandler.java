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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ledgerpay.dto.ApiErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String MERCHANT_EMAIL_ALREADY_EXISTS = "MERCHANT_EMAIL_ALREADY_EXISTS";
    private static final String MERCHANT_HAS_UNFINISHED_OPERATIONS =
            "MERCHANT_HAS_UNFINISHED_OPERATIONS";
    private static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    private static final String REFUND_NOT_FOUND = "REFUND_NOT_FOUND";
    private static final String WEBHOOK_EVENT_NOT_FOUND = "WEBHOOK_EVENT_NOT_FOUND";
    private static final String WEBHOOK_INVALID_STATE = "WEBHOOK_INVALID_STATE";
    private static final String WEBHOOK_URL_NOT_CONFIGURED = "WEBHOOK_URL_NOT_CONFIGURED";
    private static final String PAYMENT_ALREADY_PENDING = "PAYMENT_ALREADY_PENDING";
    private static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    private static final String PAYMENT_INVALID_STATE = "PAYMENT_INVALID_STATE";
    private static final String PAYMENT_NOT_REFUNDABLE = "PAYMENT_NOT_REFUNDABLE";
    private static final String INSUFFICIENT_REFUNDABLE_AMOUNT =
            "INSUFFICIENT_REFUNDABLE_AMOUNT";
    private static final String REFUND_INVALID_STATE = "REFUND_INVALID_STATE";
    private static final String ORDER_ALREADY_PAID = "ORDER_ALREADY_PAID";
    private static final String ORDER_INVALID_STATE = "ORDER_INVALID_STATE";
    private static final String ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND";
    private static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
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

    @ExceptionHandler(MerchantHasUnfinishedOperationsException.class)
    public ResponseEntity<ApiErrorResponse> handleMerchantHasUnfinishedOperations(
            MerchantHasUnfinishedOperationsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        MERCHANT_HAS_UNFINISHED_OPERATIONS,
                        exception.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderNotFound(
            OrderNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        ORDER_NOT_FOUND,
                        "Order was not found."));
    }

    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOrderId(
            InvalidOrderIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        "Invalid order ID."));
    }

    @ExceptionHandler(InvalidPaymentIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPaymentId(
            InvalidPaymentIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        "Invalid payment ID."));
    }

    @ExceptionHandler(InvalidRefundIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefundId(
            InvalidRefundIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        "Invalid refund ID."));
    }

    @ExceptionHandler(InvalidWebhookEventIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidWebhookEventId(
            InvalidWebhookEventIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        exception.getMessage()));
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        VALIDATION_ERROR,
                        "Invalid or missing Idempotency-Key header."));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentNotFound(
            PaymentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        PAYMENT_NOT_FOUND,
                        "Payment was not found."));
    }

    @ExceptionHandler(RefundNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRefundNotFound(
            RefundNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        REFUND_NOT_FOUND,
                        exception.getMessage()));
    }

    @ExceptionHandler(WebhookEventNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookEventNotFound(
            WebhookEventNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        WEBHOOK_EVENT_NOT_FOUND,
                        exception.getMessage()));
    }

    @ExceptionHandler(WebhookInvalidStateException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookInvalidState(
            WebhookInvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        WEBHOOK_INVALID_STATE,
                        exception.getMessage()));
    }

    @ExceptionHandler(WebhookUrlNotConfiguredException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookUrlNotConfigured(
            WebhookUrlNotConfiguredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        WEBHOOK_URL_NOT_CONFIGURED,
                        exception.getMessage()));
    }

    @ExceptionHandler(PaymentAlreadyPendingException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentAlreadyPending(
            PaymentAlreadyPendingException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        PAYMENT_ALREADY_PENDING,
                        "Order already has a pending Payment."));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was already used for a different request."));
    }

    @ExceptionHandler(PaymentInvalidStateException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentInvalidState(
            PaymentInvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        PAYMENT_INVALID_STATE,
                        "Payment is no longer pending."));
    }

    @ExceptionHandler(PaymentNotRefundableException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentNotRefundable(
            PaymentNotRefundableException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        PAYMENT_NOT_REFUNDABLE,
                        exception.getMessage()));
    }

    @ExceptionHandler(InsufficientRefundableAmountException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientRefundableAmount(
            InsufficientRefundableAmountException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        INSUFFICIENT_REFUNDABLE_AMOUNT,
                        exception.getMessage()));
    }

    @ExceptionHandler(RefundInvalidStateException.class)
    public ResponseEntity<ApiErrorResponse> handleRefundInvalidState(
            RefundInvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        REFUND_INVALID_STATE,
                        exception.getMessage()));
    }

    @ExceptionHandler(OrderAlreadyPaidException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderAlreadyPaid(
            OrderAlreadyPaidException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        ORDER_ALREADY_PAID,
                        "Order already has a successful Payment."));
    }

    @ExceptionHandler(OrderInvalidStateException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderInvalidState(
            OrderInvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        ORDER_INVALID_STATE,
                        exception.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEndpointNotFound(
            NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        ENDPOINT_NOT_FOUND,
                        "The requested endpoint was not found."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(new ApiErrorResponse(
                        METHOD_NOT_ALLOWED,
                        "The requested HTTP method is not allowed for this endpoint."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(exception.getHeaders())
                .build();
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
