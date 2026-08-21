package com.ledgerpay.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerpay.controller.RefundResponseMapper;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundReasonCode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundDtoTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-21T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-21T10:05:00Z");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsValidCreateRefundRequest() {
        CreateRefundRequest request = new CreateRefundRequest(
                300L,
                RefundReasonCode.CUSTOMER_REQUEST);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingOrNonPositiveCreateRefundRequestFields() {
        assertViolationFields(new CreateRefundRequest(null, null), "amount", "reasonCode");
        assertViolationFields(
                new CreateRefundRequest(0L, RefundReasonCode.OTHER),
                "amount");
        assertViolationFields(
                new CreateRefundRequest(-1L, RefundReasonCode.OTHER),
                "amount");
    }

    @Test
    void mapsRefundToPublicResponseWithoutInternalFields() throws Exception {
        Merchant merchant = new Merchant(
                "Refund DTO Merchant",
                "refund-dto@example.com",
                "a".repeat(64));
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setStatus(OrderStatus.PAID);
        Payment payment = new Payment(order, "payment-key");
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        Refund refund = new Refund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "refund-key");
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(refund, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(refund, "updatedAt", UPDATED_AT);

        RefundResponse response = RefundResponseMapper.toRefundResponse(refund);
        JsonNode json = objectMapper.valueToTree(response);

        assertEquals("re_" + refund.getId(), json.get("id").asText());
        assertEquals("pay_" + payment.getId(), json.get("paymentId").asText());
        assertEquals(300L, json.get("amount").asLong());
        assertEquals("EUR", json.get("currency").asText());
        assertEquals("CUSTOMER_REQUEST", json.get("reasonCode").asText());
        assertEquals("PENDING", json.get("status").asText());
        assertTrue(json.get("failureCode").isNull());
        assertEquals(CREATED_AT.toString(), json.get("createdAt").asText());
        assertEquals(UPDATED_AT.toString(), json.get("updatedAt").asText());
        assertFalse(json.has("merchantId"));
        assertFalse(json.has("idempotencyKey"));
        assertFalse(json.has("completedAt"));
    }

    private void assertViolationFields(CreateRefundRequest request, String... expectedFields) {
        Set<String> actualFields = validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(expectedFields), actualFields);
    }
}
