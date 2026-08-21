package com.ledgerpay.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefundCreationPostgresAcceptanceTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> merchantIds = new ArrayList<>();

    @AfterEach
    void removePersistedTestData() {
        for (UUID merchantId : merchantIds) {
            jdbcTemplate.update("DELETE FROM refund WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void acceptedRefundPersistsPendingRecordAndPaymentReservationTogether() {
        Merchant merchant = createMerchant("Accepted Refund");
        Payment payment = createSucceededPayment(merchant);

        RefundCreationResult result = refundService.createRefund(
                merchant,
                payment.getId(),
                request(300L),
                "accepted-refund-key");

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        var reloadedRefund = refundRepository.findById(result.refund().getId()).orElseThrow();
        assertFalse(result.replayed());
        assertEquals(RefundStatus.PENDING, reloadedRefund.getStatus());
        assertEquals(300L, reloadedRefund.getAmount());
        assertEquals(payment.getId(), reloadedRefund.getPayment().getId());
        assertEquals(300L, reloadedPayment.getPendingRefundAmount());
        assertEquals(0L, reloadedPayment.getRefundedAmount());
        assertEquals(
                OrderStatus.PAID,
                orderRepository.findById(payment.getOrder().getId())
                        .orElseThrow()
                        .getStatus());
    }

    @Test
    void exactReplayStillSucceedsAfterRefundCapacityIsFullyReserved() {
        Merchant merchant = createMerchant("Full Capacity Replay");
        Payment payment = createSucceededPayment(merchant);
        CreateRefundRequest request = request(1000L);

        RefundCreationResult created = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                "full-capacity-replay-key");
        RefundCreationResult replayed = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                "full-capacity-replay-key");

        assertFalse(created.replayed());
        assertTrue(replayed.replayed());
        assertEquals(created.refund().getId(), replayed.refund().getId());
        assertEquals(1L, countRefunds(merchant.getId(), "full-capacity-replay-key"));
        assertEquals(
                1000L,
                paymentRepository.findById(payment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
    }

    @Test
    void failureAfterRefundFlushRollsBackRefundAndPaymentReservation() {
        Merchant merchant = createMerchant("Rollback Refund");
        Payment payment = createSucceededPayment(merchant);
        doThrow(new IllegalStateException("forced payment persistence failure"))
                .when(paymentRepository)
                .saveAndFlush(argThat(candidate -> candidate.getId().equals(payment.getId())
                        && candidate.getPendingRefundAmount() == 300L));

        assertThrows(
                IllegalStateException.class,
                () -> refundService.createRefund(
                        merchant,
                        payment.getId(),
                        request(300L),
                        "rollback-refund-key"));

        assertEquals(0L, countRefunds(merchant.getId(), "rollback-refund-key"));
        assertEquals(
                0L,
                paymentRepository.findById(payment.getId())
                        .orElseThrow()
                        .getPendingRefundAmount());
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2)));
        merchantIds.add(merchant.getId());
        return merchant;
    }

    private Payment createSucceededPayment(Merchant merchant) {
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(merchant, 1000L));
        PaymentCreationResult creation = paymentService.createPayment(
                merchant,
                order.getId(),
                UUID.randomUUID().toString());
        paymentService.simulatePayment(
                merchant,
                creation.payment().getId(),
                PaymentSimulationOutcome.SUCCEEDED,
                null);
        return paymentRepository.findById(creation.payment().getId()).orElseThrow();
    }

    private CreateRefundRequest request(long amount) {
        return new CreateRefundRequest(amount, RefundReasonCode.CUSTOMER_REQUEST);
    }

    private long countRefunds(UUID merchantId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund WHERE merchant_id = ? AND idempotency_key = ?",
                Long.class,
                merchantId,
                idempotencyKey);
    }
}
