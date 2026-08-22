package com.ledgerpay.service;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.entity.RefundFailureCode;
import com.ledgerpay.entity.RefundSimulationOutcome;
import com.ledgerpay.entity.RefundStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.InsufficientRefundableAmountException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.PaymentNotRefundableException;
import com.ledgerpay.exception.RefundInvalidStateException;
import com.ledgerpay.exception.RefundNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRefundSummary;
import com.ledgerpay.repository.PaymentRefundSummaryRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentRefundSummaryRepository paymentRefundSummaryRepository;
    private final OrderRepository orderRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTransaction;

    public RefundService(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            PaymentRefundSummaryRepository paymentRefundSummaryRepository,
            OrderRepository orderRepository,
            WebhookEventRepository webhookEventRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentRefundSummaryRepository = paymentRefundSummaryRepository;
        this.orderRepository = orderRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public Refund getRefund(Merchant authenticatedMerchant, UUID refundId) {
        return refundRepository.findByIdAndMerchantId(
                        refundId,
                        authenticatedMerchant.getId())
                .orElseThrow(RefundNotFoundException::new);
    }

    public List<Refund> listRefundsForPayment(
            Merchant authenticatedMerchant,
            UUID paymentId) {
        paymentRepository.findByIdAndMerchantId(
                        paymentId,
                        authenticatedMerchant.getId())
                .orElseThrow(PaymentNotFoundException::new);
        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
    }

    @Transactional
    public Refund simulateRefund(
            Merchant authenticatedMerchant,
            UUID refundId,
            RefundSimulationOutcome outcome,
            RefundFailureCode failureCode) {
        Refund refund = refundRepository.findByIdAndMerchantId(
                        refundId,
                        authenticatedMerchant.getId())
                .orElseThrow(RefundNotFoundException::new);

        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new RefundInvalidStateException();
        }

        if (outcome == RefundSimulationOutcome.SUCCEEDED) {
            refund.markSucceeded();
        } else {
            refund.markFailed(failureCode);
        }

        Refund savedRefund = refundRepository.saveAndFlush(refund);
        UUID paymentId = refund.getPayment().getId();
        UUID merchantId = authenticatedMerchant.getId();

        WebhookEventType eventType;
        if (outcome == RefundSimulationOutcome.SUCCEEDED) {
            PaymentRefundSummary summary =
                    paymentRefundSummaryRepository.completeSucceededRefund(
                            paymentId,
                            merchantId,
                            refund.getAmount());
            MerchantOrder order = refund.getPayment().getOrder();
            order.setStatus(summary.refundedAmount() == summary.amount()
                    ? OrderStatus.REFUNDED
                    : OrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(order);
            eventType = WebhookEventType.REFUND_SUCCEEDED;
        } else {
            paymentRefundSummaryRepository.completeFailedRefund(
                    paymentId,
                    merchantId,
                    refund.getAmount());
            eventType = WebhookEventType.REFUND_FAILED;
        }

        webhookEventRepository.save(new WebhookEvent(
                savedRefund,
                eventType,
                createWebhookPayload(savedRefund)));

        return savedRefund;
    }

    private JsonNode createWebhookPayload(Refund refund) {
        ObjectNode refundSnapshot = objectMapper.createObjectNode();
        refundSnapshot.put("id", "re_" + refund.getId());
        refundSnapshot.put("paymentId", "pay_" + refund.getPayment().getId());
        refundSnapshot.put("amount", refund.getAmount());
        refundSnapshot.put("currency", refund.getCurrency().name());
        refundSnapshot.put("reasonCode", refund.getReasonCode().name());
        refundSnapshot.put("status", refund.getStatus().name());

        if (refund.getFailureCode() == null) {
            refundSnapshot.putNull("failureCode");
        } else {
            refundSnapshot.put("failureCode", refund.getFailureCode().name());
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("refund", refundSnapshot);
        return payload;
    }

    public RefundCreationResult createRefund(
            Merchant authenticatedMerchant,
            UUID paymentId,
            CreateRefundRequest request,
            String idempotencyKey) {
        if (!paymentRepository.existsByIdAndMerchantId(
                paymentId,
                authenticatedMerchant.getId())) {
            throw new PaymentNotFoundException();
        }

        Refund historicalRefund = refundRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        idempotencyKey)
                .orElse(null);
        if (historicalRefund != null) {
            return replayOrConflict(historicalRefund, paymentId, request);
        }

        try {
            return writeTransaction.execute(status -> createRefundInWriteTransaction(
                    authenticatedMerchant,
                    paymentId,
                    request,
                    idempotencyKey));
        } catch (DataIntegrityViolationException exception) {
            Refund winningRefund = refundRepository.findByMerchantIdAndIdempotencyKey(
                            authenticatedMerchant.getId(),
                            idempotencyKey)
                    .orElseThrow(() -> exception);
            return replayOrConflict(winningRefund, paymentId, request);
        }
    }

    private RefundCreationResult createRefundInWriteTransaction(
            Merchant authenticatedMerchant,
            UUID paymentId,
            CreateRefundRequest request,
            String idempotencyKey) {
        Payment lockedPayment = paymentRepository.findForUpdateByIdAndMerchantId(
                        paymentId,
                        authenticatedMerchant.getId())
                .orElseThrow(PaymentNotFoundException::new);

        Refund historicalRefund = refundRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        idempotencyKey)
                .orElse(null);
        if (historicalRefund != null) {
            return replayOrConflict(historicalRefund, paymentId, request);
        }

        if (lockedPayment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new PaymentNotRefundableException();
        }

        long availableRefundAmount = lockedPayment.getAmount()
                - lockedPayment.getRefundedAmount()
                - lockedPayment.getPendingRefundAmount();
        if (request.amount() > availableRefundAmount) {
            throw new InsufficientRefundableAmountException();
        }

        Refund refund = new Refund(
                lockedPayment,
                request.amount(),
                request.reasonCode(),
                idempotencyKey);
        lockedPayment.reserveRefundAmount(request.amount());

        Refund savedRefund = refundRepository.saveAndFlush(refund);
        paymentRepository.saveAndFlush(lockedPayment);
        return new RefundCreationResult(savedRefund, false);
    }

    private RefundCreationResult replayOrConflict(
            Refund historicalRefund,
            UUID requestedPaymentId,
            CreateRefundRequest request) {
        boolean sameRequest = historicalRefund.getPayment().getId().equals(requestedPaymentId)
                && historicalRefund.getAmount().equals(request.amount())
                && historicalRefund.getReasonCode() == request.reasonCode();
        if (!sameRequest) {
            throw new IdempotencyConflictException();
        }
        return new RefundCreationResult(historicalRefund, true);
    }
}
