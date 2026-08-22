package com.ledgerpay.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.dto.RefundResponse;
import com.ledgerpay.dto.SimulateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.exception.InvalidIdempotencyKeyException;
import com.ledgerpay.exception.InvalidPaymentIdException;
import com.ledgerpay.exception.InvalidRefundIdException;
import com.ledgerpay.service.RefundCreationResult;
import com.ledgerpay.service.RefundService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class RefundController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String PAYMENT_ID_PREFIX = "pay_";
    private static final String REFUND_ID_PREFIX = "re_";
    private static final String REFUND_LOCATION_PREFIX = "/api/v1/refunds/";

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @GetMapping("/refunds/{refundId}")
    public RefundResponse getRefund(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String refundId) {
        Refund refund = refundService.getRefund(
                authenticatedMerchant,
                parseRefundId(refundId));
        return RefundResponseMapper.toRefundResponse(refund);
    }

    @GetMapping("/payments/{paymentId}/refunds")
    public List<RefundResponse> listRefundsForPayment(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String paymentId) {
        return refundService.listRefundsForPayment(
                        authenticatedMerchant,
                        parsePaymentId(paymentId))
                .stream()
                .map(RefundResponseMapper::toRefundResponse)
                .toList();
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> createRefund(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String paymentId,
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        RefundCreationResult result = refundService.createRefund(
                authenticatedMerchant,
                parsePaymentId(paymentId),
                request,
                idempotencyKey);
        RefundResponse response = RefundResponseMapper.toRefundResponse(result.refund());

        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create(REFUND_LOCATION_PREFIX + response.id()))
                .body(response);
    }

    @PostMapping("/refunds/{refundId}/simulate")
    public RefundResponse simulateRefund(
            @AuthenticationPrincipal Merchant authenticatedMerchant,
            @PathVariable String refundId,
            @Valid @RequestBody SimulateRefundRequest request) {
        Refund refund = refundService.simulateRefund(
                authenticatedMerchant,
                parseRefundId(refundId),
                request.outcome(),
                request.failureCode());
        return RefundResponseMapper.toRefundResponse(refund);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.isEmpty()
                || idempotencyKey.length() > 100) {
            throw new InvalidIdempotencyKeyException();
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

    private UUID parseRefundId(String refundId) {
        if (!refundId.startsWith(REFUND_ID_PREFIX)) {
            throw new InvalidRefundIdException();
        }

        String uuidValue = refundId.substring(REFUND_ID_PREFIX.length());
        try {
            UUID refundUuid = UUID.fromString(uuidValue);
            if (!refundUuid.toString().equalsIgnoreCase(uuidValue)) {
                throw new InvalidRefundIdException();
            }
            return refundUuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRefundIdException();
        }
    }
}
