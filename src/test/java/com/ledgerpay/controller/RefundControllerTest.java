package com.ledgerpay.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.ledgerpay.config.JacksonConfiguration;
import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundSimulationOutcome;
import com.ledgerpay.exception.GlobalExceptionHandler;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.RefundNotFoundException;
import com.ledgerpay.exception.RefundInvalidStateException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;
import com.ledgerpay.service.RefundCreationResult;
import com.ledgerpay.service.RefundService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
@Import({
        JacksonConfiguration.class,
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
class RefundControllerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final String IDEMPOTENCY_KEY = "refund-controller-key";
    private static final Instant CREATED_AT = Instant.parse("2026-08-21T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-21T10:05:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void getRefundReturnsOwnedRefundResponseWithoutInternalFields() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        authenticate(merchant);
        when(refundService.getRefund(merchant, refund.getId())).thenReturn(refund);

        mockMvc.perform(get("/api/v1/refunds/re_" + refund.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("re_" + refund.getId()))
                .andExpect(jsonPath("$.paymentId").value("pay_" + payment.getId()))
                .andExpect(jsonPath("$.amount").value(300))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.merchantId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        verify(refundService).getRefund(merchant, refund.getId());
    }

    @Test
    void getRefundMapsMissingRefundToNotFound() throws Exception {
        assertGetRefundNotFound(UUID.randomUUID());
    }

    @Test
    void getRefundMapsForeignRefundToNotFound() throws Exception {
        assertGetRefundNotFound(UUID.randomUUID());
    }

    @Test
    void getRefundRejectsInvalidRefundIdBeforeService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/refunds/pay_" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid refund ID."));

        verifyNoInteractions(refundService);
    }

    @Test
    void listPaymentRefundsReturnsPlainArrayInServiceOrder() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund newerRefund = persistedRefund(
                payment,
                200L,
                RefundReasonCode.OTHER,
                "newer-key",
                UPDATED_AT);
        Refund olderRefund = persistedRefund(
                payment,
                100L,
                RefundReasonCode.CUSTOMER_REQUEST,
                "older-key",
                CREATED_AT);
        authenticate(merchant);
        when(refundService.listRefundsForPayment(merchant, payment.getId()))
                .thenReturn(List.of(newerRefund, olderRefund));

        mockMvc.perform(get("/api/v1/payments/pay_" + payment.getId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("re_" + newerRefund.getId()))
                .andExpect(jsonPath("$[0].createdAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$[1].id").value("re_" + olderRefund.getId()))
                .andExpect(jsonPath("$[1].createdAt").value(CREATED_AT.toString()));

        verify(refundService).listRefundsForPayment(merchant, payment.getId());
    }

    @Test
    void listPaymentRefundsReturnsEmptyArrayForOwnedPayment() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        authenticate(merchant);
        when(refundService.listRefundsForPayment(merchant, payment.getId()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/payments/pay_" + payment.getId() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listPaymentRefundsMapsMissingPaymentToNotFound() throws Exception {
        assertPaymentHistoryNotFound(UUID.randomUUID());
    }

    @Test
    void listPaymentRefundsMapsForeignPaymentToNotFound() throws Exception {
        assertPaymentHistoryNotFound(UUID.randomUUID());
    }

    @Test
    void newRefundReturnsCreatedWithCanonicalLocationAndPublicResponse() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        CreateRefundRequest request = request();
        authenticate(merchant);
        when(refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request,
                        IDEMPOTENCY_KEY))
                .thenReturn(new RefundCreationResult(refund, false));

        performValidCreate(payment.getId(), IDEMPOTENCY_KEY)
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "/api/v1/refunds/re_" + refund.getId()))
                .andExpect(jsonPath("$.id").value("re_" + refund.getId()))
                .andExpect(jsonPath("$.paymentId").value("pay_" + payment.getId()))
                .andExpect(jsonPath("$.amount").value(300))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.reasonCode").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureCode").value((Object) null))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.merchantId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        verify(refundService).createRefund(
                merchant,
                payment.getId(),
                request,
                IDEMPOTENCY_KEY);
    }

    @Test
    void historicalReplayReturnsOkWithoutLocation() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        authenticate(merchant);
        when(refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(),
                        IDEMPOTENCY_KEY))
                .thenReturn(new RefundCreationResult(refund, true));

        performValidCreate(payment.getId(), IDEMPOTENCY_KEY)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value("re_" + refund.getId()));
    }

    @Test
    void missingOrInvalidIdempotencyKeyIsRejectedBeforeService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        UUID paymentId = UUID.randomUUID();

        performCreate(paymentId, null, validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performCreate(paymentId, "k".repeat(101), validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(refundService);
    }

    @Test
    void whitespaceOnlyIdempotencyKeyIsPreservedLikePaymentContract() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        String whitespaceKey = "   ";
        authenticate(merchant);
        when(refundService.createRefund(merchant, payment.getId(), request(), whitespaceKey))
                .thenReturn(new RefundCreationResult(refund, false));

        performValidCreate(payment.getId(), whitespaceKey)
                .andExpect(status().isCreated());

        verify(refundService).createRefund(merchant, payment.getId(), request(), whitespaceKey);
    }

    @Test
    void invalidPaymentIdIsRejectedBeforeService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments/ord_" + UUID.randomUUID() + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid payment ID."));

        verifyNoInteractions(refundService);
    }

    @Test
    void invalidRequestBodiesAreRejectedBeforeService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        UUID paymentId = UUID.randomUUID();

        performCreate(paymentId, IDEMPOTENCY_KEY, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performCreate(paymentId, IDEMPOTENCY_KEY, "{\"amount\":0,\"reasonCode\":\"OTHER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performCreate(
                        paymentId,
                        IDEMPOTENCY_KEY,
                        "{\"amount\":1.5,\"reasonCode\":\"OTHER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performCreate(
                        paymentId,
                        IDEMPOTENCY_KEY,
                        "{\"amount\":300,\"reasonCode\":\"UNKNOWN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(refundService);
    }

    @Test
    void endpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/payments/pay_" + UUID.randomUUID() + "/refunds")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(refundService);
    }

    @Test
    void simulateSucceededRefundAcceptsAbsentFailureCode() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        refund.markSucceeded();
        authenticate(merchant);
        when(refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null))
                .thenReturn(refund);

        mockMvc.perform(post("/api/v1/refunds/re_" + refund.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.failureCode").value((Object) null));
    }

    @Test
    void simulateSucceededRefundAcceptsExplicitNullFailureCode() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        refund.markSucceeded();
        authenticate(merchant);
        when(refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null))
                .thenReturn(refund);

        mockMvc.perform(post("/api/v1/refunds/re_" + refund.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\",\"failureCode\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void simulateFailedRefundReturnsTerminalFailure() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Payment payment = persistedPayment(merchant);
        Refund refund = persistedRefund(payment);
        refund.markFailed(RefundFailureCode.REFUND_PROCESSING_ERROR);
        authenticate(merchant);
        when(refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.FAILED,
                        RefundFailureCode.REFUND_PROCESSING_ERROR))
                .thenReturn(refund);

        mockMvc.perform(post("/api/v1/refunds/re_" + refund.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"FAILED",
                                  "failureCode":"REFUND_PROCESSING_ERROR"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("REFUND_PROCESSING_ERROR"));
    }

    @Test
    void invalidSimulationCombinationIsRejectedBeforeService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/refunds/re_" + UUID.randomUUID() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"SUCCEEDED",
                                  "failureCode":"REFUND_PROCESSING_ERROR"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(refundService);
    }

    @Test
    void repeatedSimulationReturnsRefundInvalidState() throws Exception {
        Merchant merchant = authenticatedMerchant();
        Refund refund = persistedRefund(persistedPayment(merchant));
        authenticate(merchant);
        when(refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null))
                .thenThrow(new RefundInvalidStateException());

        mockMvc.perform(post("/api/v1/refunds/re_" + refund.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Refund is no longer pending."));
    }

    private org.springframework.test.web.servlet.ResultActions performValidCreate(
            UUID paymentId,
            String idempotencyKey) throws Exception {
        return performCreate(paymentId, idempotencyKey, validBody());
    }

    private void assertGetRefundNotFound(UUID refundId) throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(refundService.getRefund(merchant, refundId))
                .thenThrow(new RefundNotFoundException());

        mockMvc.perform(get("/api/v1/refunds/re_" + refundId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Refund was not found."));
    }

    private void assertPaymentHistoryNotFound(UUID paymentId) throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(refundService.listRefundsForPayment(merchant, paymentId))
                .thenThrow(new PaymentNotFoundException());

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment was not found."));
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            UUID paymentId,
            String idempotencyKey,
            String body) throws Exception {
        var requestBuilder = post("/api/v1/payments/pay_" + paymentId + "/refunds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            requestBuilder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(requestBuilder);
    }

    private void authenticate(Merchant merchant) {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
    }

    private Merchant authenticatedMerchant() {
        Merchant merchant = new Merchant("Refund Controller Merchant", "refund@example.com", API_KEY_HASH);
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private Payment persistedPayment(Merchant merchant) {
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        Payment payment = new Payment(order, "payment-key");
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }

    private Refund persistedRefund(Payment payment) {
        return persistedRefund(
                payment,
                300L,
                RefundReasonCode.CUSTOMER_REQUEST,
                IDEMPOTENCY_KEY,
                CREATED_AT);
    }

    private Refund persistedRefund(
            Payment payment,
            long amount,
            RefundReasonCode reasonCode,
            String idempotencyKey,
            Instant createdAt) {
        Refund refund = new Refund(payment, amount, reasonCode, idempotencyKey);
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(refund, "createdAt", createdAt);
        ReflectionTestUtils.setField(refund, "updatedAt", UPDATED_AT);
        return refund;
    }

    private CreateRefundRequest request() {
        return new CreateRefundRequest(300L, RefundReasonCode.CUSTOMER_REQUEST);
    }

    private String validBody() {
        return "{\"amount\":300,\"reasonCode\":\"CUSTOMER_REQUEST\"}";
    }
}
