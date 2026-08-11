package com.ledgerpay.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.dto.CreateMerchantRequest;

import jakarta.validation.Valid;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.ErrorTestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ErrorTestController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

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

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("sensitive internal failure");
        }
    }

    record NumericRequest(int quantity) {
    }
}
