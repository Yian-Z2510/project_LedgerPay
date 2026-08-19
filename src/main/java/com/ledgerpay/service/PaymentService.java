package com.ledgerpay.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentFailureCode;
import com.ledgerpay.entity.PaymentSimulationOutcome;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.WebhookEvent;
import com.ledgerpay.entity.WebhookEventType;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.OrderAlreadyPaidException;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.exception.PaymentInvalidStateException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.WebhookEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTransaction;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            WebhookEventRepository webhookEventRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public Payment getPayment(Merchant authenticatedMerchant, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(
                        paymentId,
                        authenticatedMerchant.getId())
                .orElseThrow(PaymentNotFoundException::new);
    }

    public List<Payment> listPaymentsForOrder(
            Merchant authenticatedMerchant,
            UUID orderId) {
        orderRepository.findByIdAndMerchantId(orderId, authenticatedMerchant.getId())
                .orElseThrow(OrderNotFoundException::new);
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    public PaymentCreationResult createPayment(
            Merchant authenticatedMerchant,
            UUID orderId,
            String idempotencyKey) {
        Payment historicalPayment = paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        idempotencyKey)
                .orElse(null);

        if (historicalPayment != null) {
            return replayOrConflict(historicalPayment, orderId);
        }

        try {
            return writeTransaction.execute(status -> createPaymentInWriteTransaction(
                    authenticatedMerchant,
                    orderId,
                    idempotencyKey));
        } catch (DataIntegrityViolationException exception) {
            Payment winningPayment = paymentRepository.findByMerchantIdAndIdempotencyKey(
                            authenticatedMerchant.getId(),
                            idempotencyKey)
                    .orElseThrow(() -> exception);
            return replayOrConflict(winningPayment, orderId);
        }
    }

    private PaymentCreationResult createPaymentInWriteTransaction(
            Merchant authenticatedMerchant,
            UUID orderId,
            String idempotencyKey) {
        MerchantOrder order = orderRepository.findForUpdateByIdAndMerchantId(
                        orderId,
                        authenticatedMerchant.getId())
                .orElseThrow(OrderNotFoundException::new);

        Payment historicalPayment = paymentRepository.findByMerchantIdAndIdempotencyKey(
                        authenticatedMerchant.getId(),
                        idempotencyKey)
                .orElse(null);

        if (historicalPayment != null) {
            return replayOrConflict(historicalPayment, orderId);
        }

        if (!order.getMerchant().getId().equals(authenticatedMerchant.getId())) {
            throw new OrderNotFoundException();
        }

        if (order.getStatus() == OrderStatus.PAID) {
            throw new OrderAlreadyPaidException();
        }

        if (order.getStatus() != OrderStatus.CREATED
                && order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new OrderInvalidStateException();
        }

        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PENDING)) {
            throw new PaymentAlreadyPendingException();
        }

        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCEEDED)) {
            throw new OrderAlreadyPaidException();
        }

        Payment savedPayment = paymentRepository.saveAndFlush(new Payment(order, idempotencyKey));

        if (order.getStatus() == OrderStatus.CREATED) {
            order.setStatus(OrderStatus.PAYMENT_PENDING);
            orderRepository.save(order);
        }

        return new PaymentCreationResult(savedPayment, false);
    }

    private PaymentCreationResult replayOrConflict(Payment payment, UUID requestedOrderId) {
        if (!payment.getOrder().getId().equals(requestedOrderId)) {
            throw new IdempotencyConflictException();
        }
        return new PaymentCreationResult(payment, true);
    }

    @Transactional
    public Payment simulatePayment(
            Merchant authenticatedMerchant,
            UUID paymentId,
            PaymentSimulationOutcome outcome,
            PaymentFailureCode failureCode) {
        Payment payment = paymentRepository.findByIdAndMerchantId(
                        paymentId,
                        authenticatedMerchant.getId())
                .orElseThrow(PaymentNotFoundException::new);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentInvalidStateException();
        }

        Instant completedAt = Instant.now();
        WebhookEventType eventType;

        if (outcome == PaymentSimulationOutcome.SUCCEEDED) {
            payment.markSucceeded(completedAt);
            payment.getOrder().setStatus(OrderStatus.PAID);
            orderRepository.save(payment.getOrder());
            eventType = WebhookEventType.PAYMENT_SUCCEEDED;
        } else {
            payment.markFailed(failureCode, completedAt);
            eventType = WebhookEventType.PAYMENT_FAILED;
        }

        Payment savedPayment = paymentRepository.save(payment);
        webhookEventRepository.save(new WebhookEvent(
                savedPayment,
                eventType,
                createWebhookPayload(savedPayment)));

        return savedPayment;
    }

    private JsonNode createWebhookPayload(Payment payment) {
        ObjectNode paymentSnapshot = objectMapper.createObjectNode();
        paymentSnapshot.put("id", "pay_" + payment.getId());
        paymentSnapshot.put("orderId", "ord_" + payment.getOrder().getId());
        paymentSnapshot.put("amount", payment.getAmount());
        paymentSnapshot.put("currency", payment.getCurrency().name());
        paymentSnapshot.put("status", payment.getStatus().name());

        if (payment.getFailureCode() == null) {
            paymentSnapshot.putNull("failureCode");
        } else {
            paymentSnapshot.put("failureCode", payment.getFailureCode().name());
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("payment", paymentSnapshot);
        return payload;
    }
}
