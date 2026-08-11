package com.ledgerpay.security;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.service.ApiKeyService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigurationTest.SecurityTestController.class)
@Import({
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        SecurityConfigurationTest.SecurityTestController.class
})
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void publicRegistrationWithoutCredentialsIsPermitted() throws Exception {
        mockMvc.perform(post("/api/v1/merchants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("registration reached"));

        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void publicRegistrationSkipsInvalidAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/v1/merchants")
                        .header(HttpHeaders.AUTHORIZATION, "Basic invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("registration reached"));

        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void protectedApiWithoutCredentialsReturnsStandardizedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid or missing API credentials."));
    }

    @RestController
    public static class SecurityTestController {

        @PostMapping("/api/v1/merchants")
        Map<String, String> registration() {
            return Map.of("status", "registration reached");
        }

        @GetMapping("/api/v1/protected")
        Map<String, String> protectedEndpoint() {
            return Map.of("status", "protected reached");
        }
    }
}
