package com.ledgerpay.service;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class PaymentSimulationTransactionIntegrationTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoSpyBean
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID merchantId;
    private UUID orderId;
    private UUID paymentId;

    @AfterEach
    void removePersistedTestData() {
        if (paymentId != null) {
            jdbcTemplate.update("DELETE FROM webhook_event WHERE payment_id = ?", paymentId);
            jdbcTemplate.update("DELETE FROM payment WHERE id = ?", paymentId);
        }
        if (orderId != null) {
            jdbcTemplate.update("DELETE FROM merchant_order WHERE id = ?", orderId);
        }
        if (merchantId != null) {
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackPaymentAndOrderWhenWebhookEventSaveFails() {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                "Payment Atomicity Merchant",
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
        merchantId = merchant.getId();

        MerchantOrder order = new MerchantOrder(merchant, 1000L);
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        order = orderRepository.saveAndFlush(order);
        orderId = order.getId();

        Payment payment = paymentRepository.saveAndFlush(
                new Payment(order, "atomicity-" + uniqueValue));
        paymentId = payment.getId();

        RuntimeException webhookSaveFailure =
                new RuntimeException("Simulated webhook event persistence failure.");
        doAnswer(invocation -> {
            WebhookEvent event = invocation.getArgument(0);
            assertEquals(PaymentStatus.SUCCEEDED, event.getPayment().getStatus());
            assertEquals(OrderStatus.PAID, event.getPayment().getOrder().getStatus());
            throw webhookSaveFailure;
        })
                .when(webhookEventRepository)
                .save(any(WebhookEvent.class));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> paymentService.simulatePayment(
                        merchant,
                        paymentId,
                        PaymentSimulationOutcome.SUCCEEDED,
                        null));

        assertSame(webhookSaveFailure, thrown);

        PaymentDatabaseState reloadedPayment = jdbcTemplate.queryForObject(
                """
                SELECT status, failure_code, completed_at
                FROM payment
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new PaymentDatabaseState(
                        resultSet.getString("status"),
                        resultSet.getString("failure_code"),
                        resultSet.getTimestamp("completed_at")),
                paymentId);
        String reloadedOrderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_order WHERE id = ?",
                String.class,
                orderId);
        Long webhookEventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_event WHERE payment_id = ?",
                Long.class,
                paymentId);

        assertEquals("PENDING", reloadedPayment.status());
        assertNull(reloadedPayment.failureCode());
        assertNull(reloadedPayment.completedAt());
        assertEquals("PAYMENT_PENDING", reloadedOrderStatus);
        assertEquals(0L, webhookEventCount);
    }

    private record PaymentDatabaseState(
            String status,
            String failureCode,
            Timestamp completedAt) {
    }
}
