package com.ledgerpay.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerPayAuthenticationEntryPointTest {

    @Test
    void authenticationFailureUsesSharedGenericErrorContractWithoutCredentialLeakage()
            throws Exception {
        String apiKey = "lp_test_credential-must-not-leak";
        LedgerPayAuthenticationEntryPoint entryPoint =
                new LedgerPayAuthenticationEntryPoint(new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("Rejected credential: " + apiKey));

        String responseBody = response.getContentAsString();
        assertEquals(401, response.getStatus());
        assertTrue(MediaType.parseMediaType(response.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON));
        assertEquals(
                "{\"code\":\"UNAUTHORIZED\","
                        + "\"message\":\"Invalid or missing API credentials.\"}",
                responseBody);
        assertFalse(responseBody.contains(apiKey));
        assertFalse(responseBody.contains("Rejected credential"));
    }
}
