package com.ledgerpay.entity;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookEventTest {

    private static final Instant ATTEMPTED_AT = Instant.parse("2026-08-23T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED"})
    void paymentSucceededEventRejectsNonSucceededPayment(PaymentStatus status) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebhookEvent(
                        payment(status),
                        WebhookEventType.PAYMENT_SUCCEEDED,
                        objectMapper.createObjectNode()));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "SUCCEEDED"})
    void paymentFailedEventRejectsNonFailedPayment(PaymentStatus status) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebhookEvent(
                        payment(status),
                        WebhookEventType.PAYMENT_FAILED,
                        objectMapper.createObjectNode()));
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PENDING", "FAILED"})
    void refundSucceededEventRejectsNonSucceededRefund(RefundStatus status) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebhookEvent(
                        refund(status),
                        WebhookEventType.REFUND_SUCCEEDED,
                        objectMapper.createObjectNode()));
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PENDING", "SUCCEEDED"})
    void refundFailedEventRejectsNonFailedRefund(RefundStatus status) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebhookEvent(
                        refund(status),
                        WebhookEventType.REFUND_FAILED,
                        objectMapper.createObjectNode()));
    }

    @ParameterizedTest
    @MethodSource("nonRemoteFailureCodes")
    void actualAutomaticFailureRejectsCodesThatDidNotComeFromHttp(
            WebhookFailureCode failureCode) {
        WebhookEvent event = succeededPaymentEvent();

        assertThrows(
                IllegalArgumentException.class,
                () -> event.recordAutomaticDeliveryFailed(
                        ATTEMPTED_AT,
                        failureCode,
                        3));
        assertEquals(0, event.getAttemptCount());
        assertEquals(WebhookStatus.PENDING, event.getStatus());
    }

    @ParameterizedTest
    @MethodSource("nonRemoteFailureCodes")
    void actualManualFailureRejectsCodesThatDidNotComeFromHttp(
            WebhookFailureCode failureCode) {
        WebhookEvent event = exhaustedPaymentEvent();

        assertThrows(
                IllegalArgumentException.class,
                () -> event.recordManualDeliveryFailed(ATTEMPTED_AT.plusSeconds(4), failureCode));
        assertEquals(3, event.getAttemptCount());
        assertEquals(WebhookStatus.FAILED, event.getStatus());
    }

    @Test
    void validRemoteFailureTransitionsStillWork() {
        WebhookEvent event = succeededPaymentEvent();

        event.recordAutomaticDeliveryFailed(
                ATTEMPTED_AT,
                WebhookFailureCode.HTTP_ERROR,
                1);
        event.recordManualDeliveryFailed(
                ATTEMPTED_AT.plusSeconds(1),
                WebhookFailureCode.CONNECTION_TIMEOUT);

        assertEquals(WebhookStatus.FAILED, event.getStatus());
        assertEquals(2, event.getAttemptCount());
        assertEquals(WebhookFailureCode.CONNECTION_TIMEOUT, event.getLastFailureCode());
    }

    private static Stream<WebhookFailureCode> nonRemoteFailureCodes() {
        return Stream.of(
                WebhookFailureCode.PROCESSING_ERROR,
                WebhookFailureCode.WEBHOOK_URL_NOT_CONFIGURED);
    }

    private WebhookEvent succeededPaymentEvent() {
        return new WebhookEvent(
                payment(PaymentStatus.SUCCEEDED),
                WebhookEventType.PAYMENT_SUCCEEDED,
                objectMapper.createObjectNode());
    }

    private WebhookEvent exhaustedPaymentEvent() {
        WebhookEvent event = succeededPaymentEvent();
        event.recordAutomaticDeliveryFailed(
                ATTEMPTED_AT.plusSeconds(1),
                WebhookFailureCode.HTTP_ERROR,
                3);
        event.recordAutomaticDeliveryFailed(
                ATTEMPTED_AT.plusSeconds(2),
                WebhookFailureCode.CONNECTION_TIMEOUT,
                3);
        event.recordAutomaticDeliveryFailed(
                ATTEMPTED_AT.plusSeconds(3),
                WebhookFailureCode.HTTP_ERROR,
                3);
        return event;
    }

    private Payment payment(PaymentStatus status) {
        Merchant merchant = new Merchant(
                "Domain Test Merchant",
                "domain-test@example.com",
                "a".repeat(64));
        Payment payment = new Payment(
                new MerchantOrder(merchant, 1000L),
                "domain-test-payment");
        if (status == PaymentStatus.SUCCEEDED) {
            payment.markSucceeded(ATTEMPTED_AT.minusSeconds(1));
        } else if (status == PaymentStatus.FAILED) {
            payment.markFailed(
                    PaymentFailureCode.PAYMENT_DECLINED,
                    ATTEMPTED_AT.minusSeconds(1));
        }
        return payment;
    }

    private Refund refund(RefundStatus status) {
        Refund refund = new Refund(
                payment(PaymentStatus.SUCCEEDED),
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "domain-test-refund");
        if (status == RefundStatus.SUCCEEDED) {
            refund.markSucceeded();
        } else if (status == RefundStatus.FAILED) {
            refund.markFailed(RefundFailureCode.REFUND_PROCESSING_ERROR);
        }
        return refund;
    }
}
