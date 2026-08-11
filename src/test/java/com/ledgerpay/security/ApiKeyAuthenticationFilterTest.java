package com.ledgerpay.security;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantStatus;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.service.ApiKeyService;

import jakarta.servlet.FilterChain;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final String UNAUTHORIZED_JSON =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing API credentials.\"}";

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private FilterChain filterChain;

    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(
                apiKeyService,
                merchantRepository,
                new LedgerPayAuthenticationEntryPoint(new ObjectMapper()));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validApiKeyAuthenticatesActiveMerchantAsPrincipal() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
        MockHttpServletRequest request = protectedRequest("Bearer " + API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.isAuthenticated());
        assertSame(merchant, authentication.getPrincipal());
        assertNull(authentication.getCredentials());
        assertTrue(authentication.getAuthorities().isEmpty());
        verify(apiKeyService).hashApiKey(API_KEY);
        verify(merchantRepository).findByApiKeyHash(API_KEY_HASH);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void unknownApiKeyReturnsGenericUnauthorizedResponse() throws Exception {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.empty());

        MockHttpServletResponse response = authenticate("Bearer " + API_KEY);

        assertUnauthorized(response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void inactiveMerchantReturnsGenericUnauthorizedResponse() throws Exception {
        Merchant merchant = activeMerchant();
        merchant.setStatus(MerchantStatus.INACTIVE);
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = authenticate("Bearer " + API_KEY);

        assertUnauthorized(response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void invalidApiKeyFormatFailsWithoutRepositoryLookup() throws Exception {
        MockHttpServletResponse response = authenticate("Bearer lp_test_tooShort");

        assertUnauthorized(response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void malformedBearerHeaderIsRejected() throws Exception {
        MockHttpServletResponse response = authenticate("Bearer");

        assertUnauthorized(response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void bearerHeaderWithExtraTokenIsRejected() throws Exception {
        MockHttpServletResponse response = authenticate("Bearer " + API_KEY + " extra");

        assertUnauthorized(response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void basicSchemeIsRejected() throws Exception {
        MockHttpServletResponse response = authenticate("Basic " + API_KEY);

        assertUnauthorized(response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
        MockHttpServletRequest request = protectedRequest("bEaReR " + API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertSame(merchant, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void apiKeyCredentialRemainsCaseSensitive() throws Exception {
        String caseSensitiveApiKey = "lp_test_" + "Ab".repeat(11);
        Merchant merchant = activeMerchant();
        when(apiKeyService.hashApiKey(caseSensitiveApiKey)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
        MockHttpServletRequest request = protectedRequest("Bearer " + caseSensitiveApiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(apiKeyService).hashApiKey(caseSensitiveApiKey);
        verify(apiKeyService, never()).hashApiKey(caseSensitiveApiKey.toLowerCase());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void multipleAuthorizationHeadersAreRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.setServletPath("/api/v1/protected");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertUnauthorized(response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void missingAuthorizationHeaderContinuesWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = protectedRequest(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    @Test
    void publicRegistrationSkipsInvalidAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        request.setServletPath("/api/v1/merchants");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(apiKeyService, merchantRepository);
        assertEquals(200, response.getStatus());
    }

    @Test
    void existingAuthenticatedSecurityContextIsNotReauthenticated() throws Exception {
        Merchant merchant = activeMerchant();
        Authentication existingAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                merchant,
                null,
                Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(existingAuthentication);
        MockHttpServletRequest request = protectedRequest("Basic invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertSame(existingAuthentication, SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(apiKeyService, merchantRepository);
    }

    private MockHttpServletResponse authenticate(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = protectedRequest(authorizationHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private MockHttpServletRequest protectedRequest(String authorizationHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.setServletPath("/api/v1/protected");
        if (authorizationHeader != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return request;
    }

    private Merchant activeMerchant() {
        return new Merchant("Alice Shop", "alice@example.com", "b".repeat(64));
    }

    private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
        String responseBody = response.getContentAsString();
        assertEquals(401, response.getStatus());
        assertTrue(MediaType.parseMediaType(response.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_JSON, responseBody);
        assertFalse(responseBody.contains(API_KEY));
        assertFalse(responseBody.contains(API_KEY_HASH));
    }
}
