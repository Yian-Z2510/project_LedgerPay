package com.ledgerpay.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantStatus;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.service.ApiKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PUBLIC_REGISTRATION_PATH = "/api/v1/merchants";
    private static final Pattern BEARER_HEADER_PATTERN = Pattern.compile(
            "^Bearer[\\t ]+(\\S+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "^lp_test_[A-Za-z0-9_-]{22}$");

    private final ApiKeyService apiKeyService;
    private final MerchantRepository merchantRepository;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public ApiKeyAuthenticationFilter(
            ApiKeyService apiKeyService,
            MerchantRepository merchantRepository,
            LedgerPayAuthenticationEntryPoint authenticationEntryPoint) {
        this.apiKeyService = apiKeyService;
        this.merchantRepository = merchantRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.POST.matches(request.getMethod())
                && PUBLIC_REGISTRATION_PATH.equals(requestPath);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication existingAuthentication = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuthentication != null && existingAuthentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        List<String> authorizationHeaders = Collections.list(
                request.getHeaders(HttpHeaders.AUTHORIZATION));

        if (authorizationHeaders.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authorizationHeaders.size() != 1) {
            failAuthentication(request, response);
            return;
        }

        Matcher headerMatcher = BEARER_HEADER_PATTERN.matcher(authorizationHeaders.getFirst());
        if (!headerMatcher.matches()) {
            failAuthentication(request, response);
            return;
        }

        String plaintextApiKey = headerMatcher.group(1);
        if (!API_KEY_PATTERN.matcher(plaintextApiKey).matches()) {
            failAuthentication(request, response);
            return;
        }

        String apiKeyHash = apiKeyService.hashApiKey(plaintextApiKey);
        Merchant merchant = merchantRepository.findByApiKeyHash(apiKeyHash).orElse(null);
        if (merchant == null || merchant.getStatus() != MerchantStatus.ACTIVE) {
            failAuthentication(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        merchant,
                        null,
                        Collections.emptyList());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        filterChain.doFilter(request, response);
    }

    private void failAuthentication(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException("Invalid API credentials"));
    }
}
