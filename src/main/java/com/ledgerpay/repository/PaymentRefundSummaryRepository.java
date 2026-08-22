package com.ledgerpay.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRefundSummaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentRefundSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PaymentRefundSummary completeSucceededRefund(
            UUID paymentId,
            UUID merchantId,
            long refundAmount) {
        return updateAndReturnSummary(
                """
                UPDATE payment
                SET pending_refund_amount = pending_refund_amount - ?,
                    refunded_amount = refunded_amount + ?
                WHERE id = ?
                  AND merchant_id = ?
                  AND status = 'SUCCEEDED'
                  AND pending_refund_amount >= ?
                RETURNING amount, refunded_amount, pending_refund_amount
                """,
                refundAmount,
                refundAmount,
                paymentId,
                merchantId,
                refundAmount);
    }

    public PaymentRefundSummary completeFailedRefund(
            UUID paymentId,
            UUID merchantId,
            long refundAmount) {
        return updateAndReturnSummary(
                """
                UPDATE payment
                SET pending_refund_amount = pending_refund_amount - ?
                WHERE id = ?
                  AND merchant_id = ?
                  AND status = 'SUCCEEDED'
                  AND pending_refund_amount >= ?
                RETURNING amount, refunded_amount, pending_refund_amount
                """,
                refundAmount,
                paymentId,
                merchantId,
                refundAmount);
    }

    private PaymentRefundSummary updateAndReturnSummary(String sql, Object... arguments) {
        PaymentRefundSummary summary = jdbcTemplate.query(
                        sql,
                        (resultSet, rowNumber) -> new PaymentRefundSummary(
                                resultSet.getLong("amount"),
                                resultSet.getLong("refunded_amount"),
                                resultSet.getLong("pending_refund_amount")),
                        arguments)
                .stream()
                .findFirst()
                .orElse(null);

        if (summary == null) {
            throw new IllegalStateException(
                    "Payment refund summary could not be updated consistently.");
        }
        return summary;
    }
}
