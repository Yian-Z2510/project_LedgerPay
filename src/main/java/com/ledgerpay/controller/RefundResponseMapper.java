package com.ledgerpay.controller;

import com.ledgerpay.dto.RefundResponse;
import com.ledgerpay.entity.Refund;

public final class RefundResponseMapper {

    private static final String PAYMENT_ID_PREFIX = "pay_";
    private static final String REFUND_ID_PREFIX = "re_";

    private RefundResponseMapper() {
    }

    public static RefundResponse toRefundResponse(Refund refund) {
        return new RefundResponse(
                REFUND_ID_PREFIX + refund.getId(),
                PAYMENT_ID_PREFIX + refund.getPayment().getId(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReasonCode(),
                refund.getStatus(),
                refund.getFailureCode(),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }
}
