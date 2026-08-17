package com.ledgerpay.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderStatus;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.exception.OrderAlreadyPaidException;
import com.ledgerpay.exception.OrderInvalidStateException;
import com.ledgerpay.exception.OrderNotFoundException;
import com.ledgerpay.exception.PaymentAlreadyPendingException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.repository.OrderRepository;
import com.ledgerpay.repository.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
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

    @Transactional
    public Payment createPayment(
            Merchant authenticatedMerchant,
            UUID orderId,
            String idempotencyKey) {
        MerchantOrder order = orderRepository.findForUpdateByIdAndMerchantId(
                        orderId,
                        authenticatedMerchant.getId())
                .orElseThrow(OrderNotFoundException::new);

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

        Payment savedPayment = paymentRepository.save(new Payment(order, idempotencyKey));

        if (order.getStatus() == OrderStatus.CREATED) {
            order.setStatus(OrderStatus.PAYMENT_PENDING);
            orderRepository.save(order);
        }

        return savedPayment;
    }
}
