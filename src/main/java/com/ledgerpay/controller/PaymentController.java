package com.ledgerpay.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreatePaymentRequest;
import com.ledgerpay.dto.PaymentResponse;
import com.ledgerpay.dto.SimulatePaymentRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.exception.InvalidIdempotencyKeyException;
import com.ledgerpay.exception.InvalidOrderIdException;
import com.ledgerpay.exception.InvalidPaymentIdException;
import com.ledgerpay.service.PaymentCreationResult;
import com.ledgerpay.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String ORDER_ID_PREFIX = "ord_";
    private static final String PAYMENT_ID_PREFIX = "pay_";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> createPayment(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        PaymentCreationResult result = paymentService.createPayment(
                authenticatedMerchant,
                parseOrderId(request.orderId()),
                idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(toPaymentResponse(result.payment()));
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentResponse getPayment(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String paymentId) {
        Payment payment = paymentService.getPayment(
                authenticatedMerchant,
                parsePaymentId(paymentId));
        return toPaymentResponse(payment);
    }

    @PostMapping("/payments/{paymentId}/simulate")
    public PaymentResponse simulatePayment(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String paymentId,
            @Valid @RequestBody SimulatePaymentRequest request) {
        Payment payment = paymentService.simulatePayment(
                authenticatedMerchant,
                parsePaymentId(paymentId),
                request.outcome(),
                request.failureCode());
        return toPaymentResponse(payment);
    }

    @GetMapping("/orders/{orderId}/payments")
    public List<PaymentResponse> listPaymentsForOrder(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String orderId) {
        return paymentService.listPaymentsForOrder(
                        authenticatedMerchant,
                        parseOrderId(orderId))
                .stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                PAYMENT_ID_PREFIX + payment.getId(),
                ORDER_ID_PREFIX + payment.getOrder().getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getRefundedAmount(),
                payment.getPendingRefundAmount(),
                payment.getAmount()
                        - payment.getRefundedAmount()
                        - payment.getPendingRefundAmount(),
                payment.getFailureCode(),
                payment.getCompletedAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.isEmpty()
                || idempotencyKey.length() > 100) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private UUID parseOrderId(String orderId) {
        if (!orderId.startsWith(ORDER_ID_PREFIX)) {
            throw new InvalidOrderIdException();
        }

        String uuidValue = orderId.substring(ORDER_ID_PREFIX.length());
        try {
            UUID orderUuid = UUID.fromString(uuidValue);
            if (!orderUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidOrderIdException();
            }
            return orderUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidOrderIdException();
        }
    }

    private UUID parsePaymentId(String paymentId) {
        if (!paymentId.startsWith(PAYMENT_ID_PREFIX)) {
            throw new InvalidPaymentIdException();
        }

        String uuidValue = paymentId.substring(PAYMENT_ID_PREFIX.length());
        try {
            UUID paymentUuid = UUID.fromString(uuidValue);
            if (!paymentUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidPaymentIdException();
            }
            return paymentUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidPaymentIdException();
        }
    }
}
