package com.ledgerpay.service;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerpay.dto.CreateRefundRequest;
import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.Payment;
import com.ledgerpay.entity.PaymentStatus;
import com.ledgerpay.entity.Refund;
import com.ledgerpay.exception.IdempotencyConflictException;
import com.ledgerpay.exception.InsufficientRefundableAmountException;
import com.ledgerpay.exception.PaymentNotFoundException;
import com.ledgerpay.exception.PaymentNotRefundableException;
import com.ledgerpay.exception.RefundNotFoundException;
import com.ledgerpay.repository.PaymentRepository;
import com.ledgerpay.repository.RefundRepository;

@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final TransactionTemplate writeTransaction;

    public RefundService(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
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
