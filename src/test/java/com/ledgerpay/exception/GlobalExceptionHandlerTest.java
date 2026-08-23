package com.ledgerpay.exception;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreateMerchantRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;

import jakarta.validation.Valid;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.ErrorTestController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandlerTest.ErrorTestController.class
})
class GlobalExceptionHandlerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void beanValidationFailureReturnsFieldLevelInvalidRequest() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"alice@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("name must not be blank")));
    }

    @Test
    void multipleValidationFailuresUseDeterministicFieldOrdering() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"",
                                  "email":"alice@example.com",
                                  "webhookUrl":"ftp://example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "name must not be blank; "
                                + "webhookUrl must be a valid HTTP or HTTPS URL"));
    }

    @Test
    void malformedJsonReturnsGenericInvalidRequest() throws Exception {
        assertInvalidRequestBody("{");
    }

    @Test
    void unknownJsonFieldReturnsGenericInvalidRequest() throws Exception {
        assertInvalidRequestBody("""
                {
                  "name":"Alice Shop",
                  "email":"alice@example.com",
                  "merchantId":"client-controlled"
                }
                """);
    }

    @Test
    void incompatibleJsonTypeReturnsGenericInvalidRequest() throws Exception {
        mockMvc.perform(post("/test/numeric")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":"not-a-number"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Malformed or invalid request body."));
    }

    @Test
    void duplicateMerchantEmailReturnsConflictContract() throws Exception {
        mockMvc.perform(get("/test/duplicate-email"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"code\":\"MERCHANT_EMAIL_ALREADY_EXISTS\","
                                + "\"message\":\"A merchant with this email already exists.\"}"));
    }

    @Test
    void orderNotFoundReturnsNotFoundContract() throws Exception {
        mockMvc.perform(get("/test/order-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(
                        "{\"code\":\"ORDER_NOT_FOUND\","
                                + "\"message\":\"Order was not found.\"}"));
    }

    @Test
    void refundNotFoundReturnsNotFoundContract() throws Exception {
        mockMvc.perform(get("/test/refund-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(
                        "{\"code\":\"REFUND_NOT_FOUND\","
                                + "\"message\":\"Refund was not found.\"}"));
    }

    @Test
    void paymentNotRefundableReturnsConflictContract() throws Exception {
        mockMvc.perform(get("/test/payment-not-refundable"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"code\":\"PAYMENT_NOT_REFUNDABLE\","
                                + "\"message\":\"Payment has not succeeded and cannot be refunded.\"}"));
    }

    @Test
    void insufficientRefundableAmountReturnsConflictContract() throws Exception {
        mockMvc.perform(get("/test/insufficient-refundable-amount"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"code\":\"INSUFFICIENT_REFUNDABLE_AMOUNT\","
                                + "\"message\":\"The requested refund amount exceeds the available refundable amount.\"}"));
    }

    @Test
    void unknownEndpointReturnsEndpointNotFoundContract() throws Exception {
        Merchant merchant = new Merchant("Authenticated Merchant", "merchant@example.com", API_KEY_HASH);
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));

        mockMvc.perform(get("/api/v1/unknown-endpoint")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "The requested endpoint was not found."));
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowedContractWithAllowHeader() throws Exception {
        mockMvc.perform(get("/test/validated"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(
                        "The requested HTTP method is not allowed for this endpoint."));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "text/plain")
    void missingOrUnsupportedJsonContentTypeReturnsStatusOnlyUnsupportedMediaType(
            String contentType) throws Exception {
        MockHttpServletRequestBuilder request = post("/test/validated")
                .content("""
                        {"name":"Alice Shop","email":"alice@example.com"}
                        """);
        if (contentType != null) {
            request.contentType(contentType);
        }

        mockMvc.perform(request)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string(""));
    }

    @Test
    void unexpectedExceptionReturnsGenericResponseWithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("sensitive internal failure"))));
    }

    private void assertInvalidRequestBody(String requestBody) throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Malformed or invalid request body."));
    }

    @RestController
    public static class ErrorTestController {

        @PostMapping("/test/validated")
        CreateMerchantRequest validated(@Valid @RequestBody CreateMerchantRequest request) {
            return request;
        }

        @PostMapping("/test/numeric")
        NumericRequest numeric(@RequestBody NumericRequest request) {
            return request;
        }

        @GetMapping("/test/duplicate-email")
        void duplicateEmail() {
            throw new MerchantEmailAlreadyExistsException();
        }

        @GetMapping("/test/order-not-found")
        void orderNotFound() {
            throw new OrderNotFoundException();
        }

        @GetMapping("/test/refund-not-found")
        void refundNotFound() {
            throw new RefundNotFoundException();
        }

        @GetMapping("/test/payment-not-refundable")
        void paymentNotRefundable() {
            throw new PaymentNotRefundableException();
        }

        @GetMapping("/test/insufficient-refundable-amount")
        void insufficientRefundableAmount() {
            throw new InsufficientRefundableAmountException();
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("sensitive internal failure");
        }
    }

    record NumericRequest(int quantity) {
    }
}
