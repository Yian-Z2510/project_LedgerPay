package com.ledgerpay.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundReasonCode;
import com.ledgerpay.entity.RefundSimulationOutcome;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.entity.WebhookStatus;
import com.ledgerpay.exception.RefundInvalidStateException;
import com.ledgerpay.exception.RefundNotFoundException;
import com.ledgerpay.repository.MerchantRepository;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRefundSummaryRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class RefundSimulationPostgresAcceptanceTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 10;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @MockitoSpyBean
    private WebhookEventRepository webhookEventRepository;

    @MockitoSpyBean
    private PaymentRefundSummaryRepository paymentRefundSummaryRepository;

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
            jdbcTemplate.update("DELETE FROM webhook_event WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM refund WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM payment WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant_order WHERE merchant_id = ?", merchantId);
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchantId);
        }
    }

    @Test
    void succeededRefundMovesReservationToRefundedAmountAndKeepsPaymentSucceeded() {
        Merchant merchant = createMerchant("Successful Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 300L, "success-refund-key");

        Refund result = refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund persistedRefund = loadRefund(result.getId());
        Payment persistedPayment = loadPayment(payment.getId());
        WebhookEvent persistedEvent = loadOnlyRefundWebhookEvent(refund.getId());
        assertEquals(RefundStatus.SUCCEEDED, persistedRefund.getStatus());
        assertNull(persistedRefund.getFailureCode());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(300L, persistedPayment.getRefundedAmount());
        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, loadOrderStatus(payment));
        assertRefundEventEnvelope(persistedEvent, refund, WebhookEventType.REFUND_SUCCEEDED);
        assertEquals(
                "SUCCEEDED",
                persistedEvent.getPayload().path("refund").path("status").stringValue());
        assertTrue(persistedEvent.getPayload().path("refund").path("failureCode").isNull());
    }

    @Test
    void fullSucceededRefundMarksOrderRefunded() {
        Merchant merchant = createMerchant("Full Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 1000L, "full-refund-key");

        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(1000L, persistedPayment.getRefundedAmount());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertEquals(OrderStatus.REFUNDED, loadOrderStatus(payment));
    }

    @Test
    void failedRefundReleasesReservationWithoutChangingRefundedAmountOrPaymentStatus() {
        Merchant merchant = createMerchant("Failed Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 300L, "failed-refund-key");

        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.FAILED,
                RefundFailureCode.REFUND_PROCESSING_ERROR);

        Refund persistedRefund = loadRefund(refund.getId());
        Payment persistedPayment = loadPayment(payment.getId());
        WebhookEvent persistedEvent = loadOnlyRefundWebhookEvent(refund.getId());
        assertEquals(RefundStatus.FAILED, persistedRefund.getStatus());
        assertEquals(
                RefundFailureCode.REFUND_PROCESSING_ERROR,
                persistedRefund.getFailureCode());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(0L, persistedPayment.getRefundedAmount());
        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertRefundEventEnvelope(persistedEvent, refund, WebhookEventType.REFUND_FAILED);
        assertEquals(
                "FAILED",
                persistedEvent.getPayload().path("refund").path("status").stringValue());
        assertEquals(
                "REFUND_PROCESSING_ERROR",
                persistedEvent.getPayload().path("refund").path("failureCode").stringValue());
    }

    @Test
    void partialRefundThenSecondRefundFullyRefundsPayment() {
        Merchant merchant = createMerchant("Partial Then Full Refund Lifecycle");
        Payment payment = createSucceededPayment(merchant);

        Refund firstRefund = createRefund(
                merchant,
                payment,
                300L,
                "partial-then-full-refund-key-a");

        Refund pendingFirstRefund = loadRefund(firstRefund.getId());
        Payment paymentWithFirstReservation = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, pendingFirstRefund.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, paymentWithFirstReservation.getStatus());
        assertEquals(0L, paymentWithFirstReservation.getRefundedAmount());
        assertEquals(300L, paymentWithFirstReservation.getPendingRefundAmount());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertTrue(paymentWithFirstReservation.getRefundedAmount()
                + paymentWithFirstReservation.getPendingRefundAmount()
                <= paymentWithFirstReservation.getAmount());

        refundService.simulateRefund(
                merchant,
                firstRefund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund succeededFirstRefund = loadRefund(firstRefund.getId());
        Payment paymentAfterFirstRefund = loadPayment(payment.getId());
        WebhookEvent firstSucceededEvent = loadOnlyRefundWebhookEvent(firstRefund.getId());
        assertEquals(RefundStatus.SUCCEEDED, succeededFirstRefund.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, paymentAfterFirstRefund.getStatus());
        assertEquals(300L, paymentAfterFirstRefund.getRefundedAmount());
        assertEquals(0L, paymentAfterFirstRefund.getPendingRefundAmount());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, loadOrderStatus(payment));
        assertRefundEventEnvelope(
                firstSucceededEvent,
                firstRefund,
                WebhookEventType.REFUND_SUCCEEDED);

        Refund secondRefund = createRefund(
                merchant,
                payment,
                700L,
                "partial-then-full-refund-key-b");

        Refund pendingSecondRefund = loadRefund(secondRefund.getId());
        Payment paymentWithSecondReservation = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, pendingSecondRefund.getStatus());
        assertEquals(300L, paymentWithSecondReservation.getRefundedAmount());
        assertEquals(700L, paymentWithSecondReservation.getPendingRefundAmount());
        assertTrue(paymentWithSecondReservation.getRefundedAmount()
                + paymentWithSecondReservation.getPendingRefundAmount()
                <= paymentWithSecondReservation.getAmount());

        refundService.simulateRefund(
                merchant,
                secondRefund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        List<Refund> refundHistory = refundService.listRefundsForPayment(
                merchant,
                payment.getId());
        Payment fullyRefundedPayment = loadPayment(payment.getId());
        WebhookEvent secondSucceededEvent = loadOnlyRefundWebhookEvent(secondRefund.getId());
        assertEquals(2, refundHistory.size());
        assertTrue(refundHistory.stream().anyMatch(refund -> refund.getId().equals(firstRefund.getId())
                && refund.getStatus() == RefundStatus.SUCCEEDED));
        assertTrue(refundHistory.stream().anyMatch(refund -> refund.getId().equals(secondRefund.getId())
                && refund.getStatus() == RefundStatus.SUCCEEDED));
        assertEquals(PaymentStatus.SUCCEEDED, fullyRefundedPayment.getStatus());
        assertEquals(1000L, fullyRefundedPayment.getRefundedAmount());
        assertEquals(0L, fullyRefundedPayment.getPendingRefundAmount());
        assertEquals(OrderStatus.REFUNDED, loadOrderStatus(payment));
        assertRefundEventEnvelope(
                secondSucceededEvent,
                secondRefund,
                WebhookEventType.REFUND_SUCCEEDED);
        assertEquals(1L, countRefundWebhookEvents(firstRefund.getId()));
        assertEquals(1L, countRefundWebhookEvents(secondRefund.getId()));
        assertTrue(fullyRefundedPayment.getRefundedAmount()
                + fullyRefundedPayment.getPendingRefundAmount()
                <= fullyRefundedPayment.getAmount());
    }

    @Test
    void failedRefundReleasesCapacityForNewKeySuccessfulRetry() {
        Merchant merchant = createMerchant("Failed Refund Capacity Reuse Lifecycle");
        Payment payment = createSucceededPayment(merchant);

        Refund failedRefund = createRefund(
                merchant,
                payment,
                1000L,
                "failed-capacity-reuse-refund-key-a");

        Payment paymentWithFullReservation = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, loadRefund(failedRefund.getId()).getStatus());
        assertEquals(0L, paymentWithFullReservation.getRefundedAmount());
        assertEquals(1000L, paymentWithFullReservation.getPendingRefundAmount());
        assertTrue(paymentWithFullReservation.getRefundedAmount()
                + paymentWithFullReservation.getPendingRefundAmount()
                <= paymentWithFullReservation.getAmount());

        refundService.simulateRefund(
                merchant,
                failedRefund.getId(),
                RefundSimulationOutcome.FAILED,
                RefundFailureCode.REFUND_PROCESSING_ERROR);

        Refund persistedFailedRefund = loadRefund(failedRefund.getId());
        Payment paymentAfterFailure = loadPayment(payment.getId());
        WebhookEvent failedEvent = loadOnlyRefundWebhookEvent(failedRefund.getId());
        assertEquals(RefundStatus.FAILED, persistedFailedRefund.getStatus());
        assertEquals(
                RefundFailureCode.REFUND_PROCESSING_ERROR,
                persistedFailedRefund.getFailureCode());
        assertEquals(PaymentStatus.SUCCEEDED, paymentAfterFailure.getStatus());
        assertEquals(0L, paymentAfterFailure.getRefundedAmount());
        assertEquals(0L, paymentAfterFailure.getPendingRefundAmount());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertRefundEventEnvelope(failedEvent, failedRefund, WebhookEventType.REFUND_FAILED);

        Refund successfulRetry = createRefund(
                merchant,
                payment,
                1000L,
                "failed-capacity-reuse-refund-key-b");

        Payment paymentWithReusedReservation = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, loadRefund(successfulRetry.getId()).getStatus());
        assertEquals(0L, paymentWithReusedReservation.getRefundedAmount());
        assertEquals(1000L, paymentWithReusedReservation.getPendingRefundAmount());
        assertTrue(paymentWithReusedReservation.getRefundedAmount()
                + paymentWithReusedReservation.getPendingRefundAmount()
                <= paymentWithReusedReservation.getAmount());

        refundService.simulateRefund(
                merchant,
                successfulRetry.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund stillFailedRefund = loadRefund(failedRefund.getId());
        Refund succeededRetry = loadRefund(successfulRetry.getId());
        List<Refund> refundHistory = refundService.listRefundsForPayment(
                merchant,
                payment.getId());
        Payment fullyRefundedPayment = loadPayment(payment.getId());
        WebhookEvent succeededEvent = loadOnlyRefundWebhookEvent(successfulRetry.getId());
        assertEquals(RefundStatus.FAILED, stillFailedRefund.getStatus());
        assertEquals(RefundStatus.SUCCEEDED, succeededRetry.getStatus());
        assertEquals(2, refundHistory.size());
        assertTrue(refundHistory.stream().anyMatch(refund -> refund.getId().equals(failedRefund.getId())
                && refund.getStatus() == RefundStatus.FAILED));
        assertTrue(refundHistory.stream().anyMatch(refund -> refund.getId().equals(successfulRetry.getId())
                && refund.getStatus() == RefundStatus.SUCCEEDED));
        assertEquals(PaymentStatus.SUCCEEDED, fullyRefundedPayment.getStatus());
        assertEquals(1000L, fullyRefundedPayment.getRefundedAmount());
        assertEquals(0L, fullyRefundedPayment.getPendingRefundAmount());
        assertEquals(OrderStatus.REFUNDED, loadOrderStatus(payment));
        assertRefundEventEnvelope(succeededEvent, successfulRetry, WebhookEventType.REFUND_SUCCEEDED);
        assertEquals(1L, countRefundWebhookEvents(failedRefund.getId()));
        assertEquals(1L, countRefundWebhookEvents(successfulRetry.getId()));
        assertTrue(fullyRefundedPayment.getRefundedAmount()
                + fullyRefundedPayment.getPendingRefundAmount()
                <= fullyRefundedPayment.getAmount());
    }

    @Test
    void succeededRefundExactReplayReturnsHistoricalRefund() {
        Merchant merchant = createMerchant("Succeeded Refund Historical Replay");
        Payment payment = createSucceededPayment(merchant);
        String idempotencyKey = "succeeded-historical-replay-key";
        CreateRefundRequest request = new CreateRefundRequest(
                1000L,
                RefundReasonCode.CUSTOMER_REQUEST);
        RefundCreationResult creation = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                idempotencyKey);
        Refund refund = creation.refund();

        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        Refund succeededRefund = loadRefund(refund.getId());
        Payment paymentBeforeReplay = loadPayment(payment.getId());
        int refundCountBeforeReplay = refundService.listRefundsForPayment(
                        merchant,
                        payment.getId())
                .size();
        long eventCountBeforeReplay = countRefundWebhookEvents(refund.getId());
        assertEquals(RefundStatus.SUCCEEDED, succeededRefund.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, paymentBeforeReplay.getStatus());
        assertEquals(1000L, paymentBeforeReplay.getRefundedAmount());
        assertEquals(0L, paymentBeforeReplay.getPendingRefundAmount());
        assertEquals(OrderStatus.REFUNDED, loadOrderStatus(payment));
        assertEquals(
                0L,
                paymentBeforeReplay.getAmount()
                        - paymentBeforeReplay.getRefundedAmount()
                        - paymentBeforeReplay.getPendingRefundAmount());
        assertEquals(1, refundCountBeforeReplay);
        assertEquals(1L, eventCountBeforeReplay);

        RefundCreationResult replay = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                idempotencyKey);

        Payment paymentAfterReplay = loadPayment(payment.getId());
        assertTrue(replay.replayed());
        assertEquals(refund.getId(), replay.refund().getId());
        assertEquals(RefundStatus.SUCCEEDED, replay.refund().getStatus());
        assertEquals(
                refundCountBeforeReplay,
                refundService.listRefundsForPayment(merchant, payment.getId()).size());
        assertEquals(PaymentStatus.SUCCEEDED, paymentAfterReplay.getStatus());
        assertEquals(1000L, paymentAfterReplay.getRefundedAmount());
        assertEquals(0L, paymentAfterReplay.getPendingRefundAmount());
        assertEquals(OrderStatus.REFUNDED, loadOrderStatus(payment));
        assertEquals(eventCountBeforeReplay, countRefundWebhookEvents(refund.getId()));
    }

    @Test
    void failedRefundExactReplayReturnsHistoricalRefund() {
        Merchant merchant = createMerchant("Failed Refund Historical Replay");
        Payment payment = createSucceededPayment(merchant);
        String idempotencyKey = "failed-historical-replay-key";
        CreateRefundRequest request = new CreateRefundRequest(
                1000L,
                RefundReasonCode.CUSTOMER_REQUEST);
        RefundCreationResult creation = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                idempotencyKey);
        Refund refund = creation.refund();

        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.FAILED,
                RefundFailureCode.REFUND_PROCESSING_ERROR);

        Refund failedRefund = loadRefund(refund.getId());
        Payment paymentBeforeReplay = loadPayment(payment.getId());
        int refundCountBeforeReplay = refundService.listRefundsForPayment(
                        merchant,
                        payment.getId())
                .size();
        long eventCountBeforeReplay = countRefundWebhookEvents(refund.getId());
        assertEquals(RefundStatus.FAILED, failedRefund.getStatus());
        assertEquals(
                RefundFailureCode.REFUND_PROCESSING_ERROR,
                failedRefund.getFailureCode());
        assertEquals(PaymentStatus.SUCCEEDED, paymentBeforeReplay.getStatus());
        assertEquals(0L, paymentBeforeReplay.getRefundedAmount());
        assertEquals(0L, paymentBeforeReplay.getPendingRefundAmount());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertEquals(
                1000L,
                paymentBeforeReplay.getAmount()
                        - paymentBeforeReplay.getRefundedAmount()
                        - paymentBeforeReplay.getPendingRefundAmount());
        assertEquals(1, refundCountBeforeReplay);
        assertEquals(1L, eventCountBeforeReplay);

        RefundCreationResult replay = refundService.createRefund(
                merchant,
                payment.getId(),
                request,
                idempotencyKey);

        Payment paymentAfterReplay = loadPayment(payment.getId());
        assertTrue(replay.replayed());
        assertEquals(refund.getId(), replay.refund().getId());
        assertEquals(RefundStatus.FAILED, replay.refund().getStatus());
        assertEquals(
                RefundFailureCode.REFUND_PROCESSING_ERROR,
                replay.refund().getFailureCode());
        assertEquals(
                refundCountBeforeReplay,
                refundService.listRefundsForPayment(merchant, payment.getId()).size());
        assertEquals(PaymentStatus.SUCCEEDED, paymentAfterReplay.getStatus());
        assertEquals(0L, paymentAfterReplay.getRefundedAmount());
        assertEquals(0L, paymentAfterReplay.getPendingRefundAmount());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertEquals(eventCountBeforeReplay, countRefundWebhookEvents(refund.getId()));
    }

    @Test
    void succeededRefundRejectsRepeatedSimulationWithoutChangingSummariesAgain() {
        Merchant merchant = createMerchant("Repeated Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 300L, "repeat-refund-key");
        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.SUCCEEDED,
                null);

        assertThrows(
                RefundInvalidStateException.class,
                () -> refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.FAILED,
                        RefundFailureCode.REFUND_PROCESSING_ERROR));

        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(300L, persistedPayment.getRefundedAmount());
    }

    @Test
    void failedRefundRejectsRepeatedSimulationWithoutChangingSummariesAgain() {
        Merchant merchant = createMerchant("Repeated Failed Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 300L, "repeat-failed-refund-key");
        refundService.simulateRefund(
                merchant,
                refund.getId(),
                RefundSimulationOutcome.FAILED,
                RefundFailureCode.REFUND_PROCESSING_ERROR);

        assertThrows(
                RefundInvalidStateException.class,
                () -> refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null));

        Refund persistedRefund = loadRefund(refund.getId());
        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(RefundStatus.FAILED, persistedRefund.getStatus());
        assertEquals(
                RefundFailureCode.REFUND_PROCESSING_ERROR,
                persistedRefund.getFailureCode());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(0L, persistedPayment.getRefundedAmount());
        assertEquals(1L, countRefundWebhookEvents(refund.getId()));
    }

    @Test
    void foreignMerchantCannotSimulateOwnedRefund() {
        Merchant owner = createMerchant("Refund Simulation Owner");
        Merchant foreignMerchant = createMerchant("Foreign Refund Simulator");
        Payment payment = createSucceededPayment(owner);
        Refund refund = createRefund(owner, payment, 300L, "foreign-simulation-key");

        assertThrows(
                RefundNotFoundException.class,
                () -> refundService.simulateRefund(
                        foreignMerchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null));

        Refund persistedRefund = loadRefund(refund.getId());
        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, persistedRefund.getStatus());
        assertEquals(300L, persistedPayment.getPendingRefundAmount());
        assertEquals(0L, persistedPayment.getRefundedAmount());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertEquals(0L, countRefundWebhookEvents(refund.getId()));
    }

    @Test
    void differentRefundsCompletingConcurrentlyDoNotLosePaymentSummaryUpdates()
            throws Exception {
        Merchant merchant = createMerchant("Concurrent Refund Simulation");
        Payment payment = createSucceededPayment(merchant);
        Refund firstRefund = createRefund(merchant, payment, 300L, "concurrent-refund-a");
        Refund secondRefund = createRefund(merchant, payment, 400L, "concurrent-refund-b");
        CyclicBarrier updateBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            updateBarrier.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        })
                .when(paymentRefundSummaryRepository)
                .completeSucceededRefund(any(UUID.class), any(UUID.class), anyLong());

        List<Refund> results = runConcurrently(
                () -> refundService.simulateRefund(
                        merchant,
                        firstRefund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null),
                () -> refundService.simulateRefund(
                        merchant,
                        secondRefund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null));

        assertTrue(results.stream().allMatch(refund -> refund.getStatus() == RefundStatus.SUCCEEDED));
        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(0L, persistedPayment.getPendingRefundAmount());
        assertEquals(700L, persistedPayment.getRefundedAmount());
        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertEquals(RefundStatus.SUCCEEDED, loadRefund(firstRefund.getId()).getStatus());
        assertEquals(RefundStatus.SUCCEEDED, loadRefund(secondRefund.getId()).getStatus());
    }

    @Test
    void webhookFailureRollsBackRefundPaymentAndOrderTogether() {
        Merchant merchant = createMerchant("Refund Simulation Rollback");
        Payment payment = createSucceededPayment(merchant);
        Refund refund = createRefund(merchant, payment, 300L, "rollback-simulation-key");
        RuntimeException webhookFailure = new RuntimeException("forced webhook save failure");
        doThrow(webhookFailure)
                .when(webhookEventRepository)
                .save(any(WebhookEvent.class));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> refundService.simulateRefund(
                        merchant,
                        refund.getId(),
                        RefundSimulationOutcome.SUCCEEDED,
                        null));

        assertSame(webhookFailure, thrown);
        Refund persistedRefund = loadRefund(refund.getId());
        Payment persistedPayment = loadPayment(payment.getId());
        assertEquals(RefundStatus.PENDING, persistedRefund.getStatus());
        assertNull(persistedRefund.getFailureCode());
        assertEquals(300L, persistedPayment.getPendingRefundAmount());
        assertEquals(0L, persistedPayment.getRefundedAmount());
        assertEquals(PaymentStatus.SUCCEEDED, persistedPayment.getStatus());
        assertEquals(OrderStatus.PAID, loadOrderStatus(payment));
        assertEquals(0L, countRefundWebhookEvents(refund.getId()));
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
        return loadPayment(creation.payment().getId());
    }

    private Refund createRefund(
            Merchant merchant,
            Payment payment,
            long amount,
            String idempotencyKey) {
        return refundService.createRefund(
                        merchant,
                        payment.getId(),
                        new CreateRefundRequest(amount, RefundReasonCode.CUSTOMER_REQUEST),
                        idempotencyKey)
                .refund();
    }

    private Payment loadPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private Refund loadRefund(UUID refundId) {
        return refundRepository.findById(refundId).orElseThrow();
    }

    private OrderStatus loadOrderStatus(Payment payment) {
        return orderRepository.findById(payment.getOrder().getId()).orElseThrow().getStatus();
    }

    private WebhookEvent loadOnlyRefundWebhookEvent(UUID refundId) {
        List<UUID> eventIds = jdbcTemplate.query(
                "SELECT id FROM webhook_event WHERE refund_id = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                refundId);
        assertEquals(1, eventIds.size());
        return webhookEventRepository.findById(eventIds.getFirst()).orElseThrow();
    }

    private long countRefundWebhookEvents(UUID refundId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_event WHERE refund_id = ?",
                Long.class,
                refundId);
    }

    private void assertRefundEventEnvelope(
            WebhookEvent event,
            Refund refund,
            WebhookEventType expectedType) {
        assertEquals(expectedType, event.getEventType());
        assertEquals(refund.getId(), event.getRefund().getId());
        assertNull(event.getPayment());
        assertEquals(WebhookStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNull(event.getLastAttemptAt());
        assertNull(event.getDeliveredAt());
        assertNull(event.getLastFailureCode());
        assertEquals(
                "re_" + refund.getId(),
                event.getPayload().path("refund").path("id").stringValue());
        assertEquals(
                "pay_" + refund.getPayment().getId(),
                event.getPayload().path("refund").path("paymentId").stringValue());
        assertEquals(
                refund.getAmount().longValue(),
                event.getPayload().path("refund").path("amount").longValue());
        assertEquals(
                "EUR",
                event.getPayload().path("refund").path("currency").stringValue());
        assertEquals(
                "CUSTOMER_REQUEST",
                event.getPayload().path("refund").path("reasonCode").stringValue());
    }

    private List<Refund> runConcurrently(
            Supplier<Refund> firstOperation,
            Supplier<Refund> secondOperation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Refund> first = executor.submit(
                    () -> runAfterStartSignal(firstOperation, ready, start));
            Future<Refund> second = executor.submit(
                    () -> runAfterStartSignal(secondOperation, ready, start));
            assertTrue(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    CONCURRENCY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS));
        }
    }

    private Refund runAfterStartSignal(
            Supplier<Refund> operation,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return operation.get();
    }
}
