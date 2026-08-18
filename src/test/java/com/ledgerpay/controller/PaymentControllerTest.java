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
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.exception.GlobalExceptionHandler;
import com.ledgerpay.exception.OrderAlreadyPaidException;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.exception.PaymentInvalidStateException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;
import com.ledgerpay.service.PaymentService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({
        JacksonConfiguration.class,
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
class PaymentControllerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final String IDEMPOTENCY_KEY = "payment-order-001";
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-17T10:05:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-17T10:03:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void createPaymentReturnsCompleteCreatedResponseWithoutLocationHeader() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.CREATED);
        Payment payment = pendingPayment(order, UUID.randomUUID(), IDEMPOTENCY_KEY);
        authenticate(merchant);
        when(paymentService.createPayment(merchant, order.getId(), IDEMPOTENCY_KEY))
                .thenReturn(payment);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(order.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value("pay_" + payment.getId()))
                .andExpect(jsonPath("$.orderId").value("ord_" + order.getId()))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.refundedAmount").value(0))
                .andExpect(jsonPath("$.pendingRefundAmount").value(0))
                .andExpect(jsonPath("$.availableRefundAmount").value(1000))
                .andExpect(jsonPath("$.failureCode").value((Object) null))
                .andExpect(jsonPath("$.completedAt").value((Object) null))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.merchantId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());

        verify(paymentService).createPayment(merchant, order.getId(), IDEMPOTENCY_KEY);
    }

    @Test
    void createPaymentRejectsMissingOrderIdWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("orderId must not be blank"));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentRejectsClientControlledFieldsWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId":"ord_%s",
                                  "amount":1,
                                  "merchantId":"mer_client-controlled",
                                  "idempotencyKey":"body-key"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed or invalid request body."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentRejectsInvalidOrderIdWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"pay_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid order ID."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentRejectsMissingIdempotencyKeyWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid or missing Idempotency-Key header."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentRejectsInvalidIdempotencyKeyWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid or missing Idempotency-Key header."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentRejectsEmptyIdempotencyKeyWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Invalid or missing Idempotency-Key header."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPaymentPreservesWhitespaceOnlyIdempotencyKey() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.CREATED);
        String whitespaceKey = "   ";
        Payment payment = pendingPayment(order, UUID.randomUUID(), whitespaceKey);
        authenticate(merchant);
        when(paymentService.createPayment(merchant, order.getId(), whitespaceKey))
                .thenReturn(payment);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", whitespaceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(order.getId())))
                .andExpect(status().isCreated());

        verify(paymentService).createPayment(merchant, order.getId(), whitespaceKey);
    }

    @Test
    void createPaymentMapsOrderNotFound() throws Exception {
        assertCreateError(
                new OrderNotFoundException(),
                404,
                "ORDER_NOT_FOUND",
                "Order was not found.");
    }

    @Test
    void createPaymentMapsPaymentAlreadyPending() throws Exception {
        assertCreateError(
                new PaymentAlreadyPendingException(),
                409,
                "PAYMENT_ALREADY_PENDING",
                "Order already has a pending Payment.");
    }

    @Test
    void createPaymentMapsOrderAlreadyPaid() throws Exception {
        assertCreateError(
                new OrderAlreadyPaidException(),
                409,
                "ORDER_ALREADY_PAID",
                "Order already has a successful Payment.");
    }

    @Test
    void createPaymentMapsOrderInvalidState() throws Exception {
        assertCreateError(
                new OrderInvalidStateException(),
                409,
                "ORDER_INVALID_STATE",
                "Order cannot accept a Payment in its current state.");
    }

    @Test
    void getPaymentReturnsOwnedPayment() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.PAID);
        Payment payment = succeededPayment(order, UUID.randomUUID(), "successful-payment-key");
        authenticate(merchant);
        when(paymentService.getPayment(merchant, payment.getId())).thenReturn(payment);

        mockMvc.perform(get("/api/v1/payments/pay_" + payment.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pay_" + payment.getId()))
                .andExpect(jsonPath("$.orderId").value("ord_" + order.getId()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refundedAmount").value(200))
                .andExpect(jsonPath("$.pendingRefundAmount").value(300))
                .andExpect(jsonPath("$.availableRefundAmount").value(500))
                .andExpect(jsonPath("$.failureCode").value((Object) null))
                .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()));

        verify(paymentService).getPayment(merchant, payment.getId());
    }

    @Test
    void getPaymentMapsMissingOrCrossMerchantPaymentToNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        authenticate(merchant);
        when(paymentService.getPayment(merchant, paymentId))
                .thenThrow(new PaymentNotFoundException());

        mockMvc.perform(get("/api/v1/payments/pay_" + paymentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment was not found."));
    }

    @Test
    void getPaymentRejectsInvalidPaymentIdWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/payments/ord_" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid payment ID."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void listPaymentsForOrderReturnsMappedListInServiceOrder() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.PAYMENT_PENDING);
        Payment newerPayment = pendingPayment(order, UUID.randomUUID(), "newer-payment-key");
        Payment olderPayment = failedPayment(order, UUID.randomUUID(), "older-payment-key");
        authenticate(merchant);
        when(paymentService.listPaymentsForOrder(merchant, order.getId()))
                .thenReturn(List.of(newerPayment, olderPayment));

        mockMvc.perform(get("/api/v1/orders/ord_" + order.getId() + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("pay_" + newerPayment.getId()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].id").value("pay_" + olderPayment.getId()))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureCode").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$[1].completedAt").value(COMPLETED_AT.toString()));

        verify(paymentService).listPaymentsForOrder(merchant, order.getId());
    }

    @Test
    void listPaymentsForOrderReturnsEmptyArray() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.CREATED);
        authenticate(merchant);
        when(paymentService.listPaymentsForOrder(merchant, order.getId())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orders/ord_" + order.getId() + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listPaymentsForOrderMapsMissingOrCrossMerchantOrderToNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID orderId = UUID.randomUUID();
        authenticate(merchant);
        when(paymentService.listPaymentsForOrder(merchant, orderId))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/api/v1/orders/ord_" + orderId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Order was not found."));
    }

    @Test
    void listPaymentsForOrderRejectsInvalidOrderIdWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/orders/pay_" + UUID.randomUUID() + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid order ID."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void simulatePaymentSucceededReturnsCompletePaymentResponse() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.PAID);
        Payment payment = succeededPayment(order, UUID.randomUUID(), "simulate-success-key");
        authenticate(merchant);
        when(paymentService.simulatePayment(
                        merchant,
                        payment.getId(),
                        PaymentSimulationOutcome.SUCCEEDED,
                        null))
                .thenReturn(payment);

        mockMvc.perform(post("/api/v1/payments/pay_" + payment.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"SUCCEEDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value("pay_" + payment.getId()))
                .andExpect(jsonPath("$.orderId").value("ord_" + order.getId()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.failureCode").value((Object) null))
                .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()));

        verify(paymentService).simulatePayment(
                merchant,
                payment.getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);
    }

    @Test
    void simulatePaymentSucceededAcceptsExplicitNullFailureCode() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.PAID);
        Payment payment = succeededPayment(order, UUID.randomUUID(), "explicit-null-key");
        authenticate(merchant);
        when(paymentService.simulatePayment(
                        merchant,
                        payment.getId(),
                        PaymentSimulationOutcome.SUCCEEDED,
                        null))
                .thenReturn(payment);

        mockMvc.perform(post("/api/v1/payments/pay_" + payment.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"SUCCEEDED",
                                  "failureCode":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void simulatePaymentFailedReturnsCompletePaymentResponse() throws Exception {
        Merchant merchant = authenticatedMerchant();
        MerchantOrder order = persistedOrder(merchant, OrderStatus.PAYMENT_PENDING);
        Payment payment = failedPayment(order, UUID.randomUUID(), "simulate-failure-key");
        authenticate(merchant);
        when(paymentService.simulatePayment(
                        merchant,
                        payment.getId(),
                        PaymentSimulationOutcome.FAILED,
                        PaymentFailureCode.PAYMENT_DECLINED))
                .thenReturn(payment);

        mockMvc.perform(post("/api/v1/payments/pay_" + payment.getId() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"FAILED",
                                  "failureCode":"PAYMENT_DECLINED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()));

        verify(paymentService).simulatePayment(
                merchant,
                payment.getId(),
                PaymentSimulationOutcome.FAILED,
                PaymentFailureCode.PAYMENT_DECLINED);
    }

    @Test
    void simulatePaymentRejectsInvalidPaymentIdWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments/ord_" + UUID.randomUUID() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid payment ID."));

        verifyNoInteractions(paymentService);
    }

    @Test
    void simulatePaymentRejectsMissingOutcome() throws Exception {
        assertInvalidSimulationBody("{}");
    }

    @Test
    void simulatePaymentRejectsUnknownOutcome() throws Exception {
        assertInvalidSimulationBody("{\"outcome\":\"COMPLETED\"}");
    }

    @Test
    void simulatePaymentRejectsPendingOutcome() throws Exception {
        assertInvalidSimulationBody("{\"outcome\":\"PENDING\"}");
    }

    @Test
    void simulatePaymentRejectsFailedOutcomeWithoutFailureCode() throws Exception {
        assertInvalidSimulationBody("{\"outcome\":\"FAILED\"}");
    }

    @Test
    void simulatePaymentRejectsSucceededOutcomeWithFailureCode() throws Exception {
        assertInvalidSimulationBody("""
                {
                  "outcome":"SUCCEEDED",
                  "failureCode":"PAYMENT_DECLINED"
                }
                """);
    }

    @Test
    void simulatePaymentRejectsUnknownFailureCode() throws Exception {
        assertInvalidSimulationBody("""
                {
                  "outcome":"FAILED",
                  "failureCode":"BANK_ERROR"
                }
                """);
    }

    @Test
    void simulatePaymentRejectsMalformedJson() throws Exception {
        assertInvalidSimulationBody("{");
    }

    @Test
    void simulatePaymentRejectsUnknownJsonField() throws Exception {
        assertInvalidSimulationBody("""
                {
                  "outcome":"SUCCEEDED",
                  "merchantId":"mer_client-controlled"
                }
                """);
    }

    @Test
    void simulatePaymentMapsMissingOrCrossMerchantPaymentToNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        authenticate(merchant);
        when(paymentService.simulatePayment(
                        merchant,
                        paymentId,
                        PaymentSimulationOutcome.SUCCEEDED,
                        null))
                .thenThrow(new PaymentNotFoundException());

        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment was not found."));
    }

    @Test
    void simulatePaymentMapsTerminalPaymentToConflict() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID paymentId = UUID.randomUUID();
        authenticate(merchant);
        when(paymentService.simulatePayment(
                        merchant,
                        paymentId,
                        PaymentSimulationOutcome.SUCCEEDED,
                        null))
                .thenThrow(new PaymentInvalidStateException());

        mockMvc.perform(post("/api/v1/payments/pay_" + paymentId + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Payment is no longer pending."));
    }

    @Test
    void simulatePaymentRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/payments/pay_" + UUID.randomUUID() + "/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(paymentService);
    }

    private void assertCreateError(
            RuntimeException exception,
            int expectedStatus,
            String expectedCode,
            String expectedMessage) throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID orderId = UUID.randomUUID();
        authenticate(merchant);
        when(paymentService.createPayment(merchant, orderId, IDEMPOTENCY_KEY))
                .thenThrow(exception);

        mockMvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ord_%s"}
                                """.formatted(orderId)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    private void assertInvalidSimulationBody(String requestBody) throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/payments/pay_" + UUID.randomUUID() + "/simulate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(paymentService);
    }

    private void authenticate(Merchant merchant) {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
    }

    private Merchant authenticatedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", API_KEY_HASH);
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        return merchant;
    }

    private MerchantOrder persistedOrder(Merchant merchant, OrderStatus status) {
        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setStatus(status);
        return order;
    }

    private Payment pendingPayment(MerchantOrder order, UUID paymentId, String idempotencyKey) {
        Payment payment = new Payment(order, idempotencyKey);
        setPaymentPersistenceFields(payment, paymentId);
        return payment;
    }

    private Payment succeededPayment(MerchantOrder order, UUID paymentId, String idempotencyKey) {
        Payment payment = new Payment(order, idempotencyKey);
        payment.markSucceeded(COMPLETED_AT);
        ReflectionTestUtils.setField(payment, "refundedAmount", 200L);
        ReflectionTestUtils.setField(payment, "pendingRefundAmount", 300L);
        setPaymentPersistenceFields(payment, paymentId);
        return payment;
    }

    private Payment failedPayment(MerchantOrder order, UUID paymentId, String idempotencyKey) {
        Payment payment = new Payment(order, idempotencyKey);
        payment.markFailed(PaymentFailureCode.PAYMENT_DECLINED, COMPLETED_AT);
        setPaymentPersistenceFields(payment, paymentId);
        return payment;
    }

    private void setPaymentPersistenceFields(Payment payment, UUID paymentId) {
        ReflectionTestUtils.setField(payment, "id", paymentId);
        ReflectionTestUtils.setField(payment, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(payment, "updatedAt", UPDATED_AT);
    }
}
