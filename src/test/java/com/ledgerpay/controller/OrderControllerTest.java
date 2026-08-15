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
import com.ledgerpay.dto.CreateOrderRequest;
import com.ledgerpay.dto.OrderResponse;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.exception.GlobalExceptionHandler;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.security.LedgerPayAuthenticationEntryPoint;
import com.ledgerpay.security.SecurityConfiguration;
import com.ledgerpay.service.ApiKeyService;
import com.ledgerpay.service.OrderService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({
        JacksonConfiguration.class,
        SecurityConfiguration.class,
        LedgerPayAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
class OrderControllerTest {

    private static final String API_KEY = "lp_test_" + "A".repeat(22);
    private static final String API_KEY_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-11T11:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MerchantRepository merchantRepository;

    @Test
    void createOrderReturnsCreatedResponseWithoutLocationHeader() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID orderId = UUID.randomUUID();
        OrderResponse response = orderResponse(orderId, 1000L);
        authenticate(merchant);
        when(orderService.createOrder(merchant, new CreateOrderRequest(1000L)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value("ord_" + orderId))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.cancelledAt").value((Object) null));

        verify(orderService).createOrder(merchant, new CreateOrderRequest(1000L));
    }

    @Test
    void floatingPointAmountReturnsValidationErrorWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Malformed or invalid request body."));

        verifyNoInteractions(orderService);
    }

    @Test
    void numericStringAmountReturnsValidationErrorWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":"1000"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "Malformed or invalid request body."));

        verifyNoInteractions(orderService);
    }

    @Test
    void invalidCreateRequestReturnsValidationErrorWithoutCallingService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("amount must be greater than 0"));

        verifyNoInteractions(orderService);
    }

    @Test
    void listOrdersReturnsDirectJsonArray() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();
        authenticate(merchant);
        when(orderService.listOrders(merchant)).thenReturn(List.of(
                orderResponse(firstOrderId, 2500L),
                orderResponse(secondOrderId, 1500L)));

        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ord_" + firstOrderId))
                .andExpect(jsonPath("$[0].amount").value(2500))
                .andExpect(jsonPath("$[1].id").value("ord_" + secondOrderId));

        verify(orderService).listOrders(merchant);
    }

    @Test
    void listOrdersReturnsEmptyArrayWhenMerchantHasNoOrders() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);
        when(orderService.listOrders(merchant)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(orderService).listOrders(merchant);
    }

    @Test
    void getOrderParsesPrefixedIdAndPassesUuidToService() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID orderId = UUID.randomUUID();
        authenticate(merchant);
        when(orderService.getOrder(merchant, orderId)).thenReturn(orderResponse(orderId, 2500L));

        mockMvc.perform(get("/api/v1/orders/ord_" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ord_" + orderId))
                .andExpect(jsonPath("$.amount").value(2500));

        verify(orderService).getOrder(merchant, orderId);
    }

    @Test
    void invalidOrderIdPrefixReturnsValidationError() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/orders/pay_" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid order ID."));

        verifyNoInteractions(orderService);
    }

    @Test
    void malformedOrderUuidReturnsValidationError() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/orders/ord_not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid order ID."));

        verifyNoInteractions(orderService);
    }

    @Test
    void nonCanonicalOrderUuidReturnsValidationError() throws Exception {
        Merchant merchant = authenticatedMerchant();
        authenticate(merchant);

        mockMvc.perform(get("/api/v1/orders/ord_1-1-1-1-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid order ID."));

        verifyNoInteractions(orderService);
    }

    @Test
    void missingOrCrossMerchantOrderReturnsOrderNotFound() throws Exception {
        Merchant merchant = authenticatedMerchant();
        UUID orderId = UUID.randomUUID();
        authenticate(merchant);
        when(orderService.getOrder(merchant, orderId)).thenThrow(new OrderNotFoundException());

        mockMvc.perform(get("/api/v1/orders/ord_" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Order was not found."));
    }

    @Test
    void orderEndpointWithoutCredentialsReturnsGenericUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(orderService);
    }

    private void authenticate(Merchant merchant) {
        when(apiKeyService.hashApiKey(API_KEY)).thenReturn(API_KEY_HASH);
        when(merchantRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(merchant));
    }

    private Merchant authenticatedMerchant() {
        Merchant merchant = new Merchant("Alice Shop", "alice@example.com", API_KEY_HASH);
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(merchant, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(merchant, "updatedAt", UPDATED_AT);
        return merchant;
    }

    private OrderResponse orderResponse(UUID orderId, Long amount) {
        return new OrderResponse(
                "ord_" + orderId,
                amount,
                OrderCurrency.EUR,
                OrderStatus.CREATED,
                null,
                CREATED_AT,
                UPDATED_AT);
    }
}
