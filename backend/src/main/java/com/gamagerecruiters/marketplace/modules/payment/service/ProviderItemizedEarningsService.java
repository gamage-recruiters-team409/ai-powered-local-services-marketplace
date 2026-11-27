package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsResponse;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Retrieves financial breakdowns for provider-owned recognized payments.
 *
 * Provider ownership is established through committed recognition events.
 * Financial calculations reuse the original payment commission snapshot
 * and the existing cumulative refund calculation policy.
 */
@Service
public class ProviderItemizedEarningsService {

    private static final String SUPPORTED_CURRENCY = "LKR";
    private static final String PENDING_EVENT_PREFIX = "earning-pending:";
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_PAGE_OFFSET = 100_000L;

    private static final Set<PaymentStatus> RECOGNIZED_PAYMENT_STATUSES =
            Set.of(
                    PaymentStatus.CAPTURED,
                    PaymentStatus.RELEASED,
                    PaymentStatus.REFUND_PENDING,
                    PaymentStatus.PARTIALLY_REFUNDED,
                    PaymentStatus.REFUNDED);

    private final EarningsLedgerRepository ledgerRepository;
    private final PaymentRepository paymentRepository;
    private final RefundCommissionCalculator refundCalculator;

    public ProviderItemizedEarningsService(
            EarningsLedgerRepository ledgerRepository,
            PaymentRepository paymentRepository,
            RefundCommissionCalculator refundCalculator) {

        this.ledgerRepository = ledgerRepository;
        this.paymentRepository = paymentRepository;
        this.refundCalculator = refundCalculator;
    }

    /**
     * Retrieves a bounded page of recognized earnings for the
     * authenticated provider.
     *
     * @param providerId trusted authenticated provider identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return paginated recognized payment breakdowns
     */
    public ItemizedEarningsPageResponse getItemizedEarnings(
            String providerId,
            int page,
            int size) {

        if (providerId == null || providerId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access earnings");
        }

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || (long) page * size > MAX_PAGE_OFFSET) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGINATION",
                    "Page must be non-negative, size must be between 1 and 100, and offset must not exceed 100000");
        }

        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("sequenceNumber"),
                        Sort.Order.desc("id")));

        Page<EarningsLedgerEntry> recognitionEvents =
                ledgerRepository.findByProviderIdAndCurrencyAndEventType(
                        providerId,
                        SUPPORTED_CURRENCY,
                        EarningsLedgerEventType.EARNING_PENDING,
                        pageable);

        Set<String> paymentIds = new HashSet<>();

        for (EarningsLedgerEntry entry : recognitionEvents.getContent()) {
            validateRecognition(entry, providerId);

            if (!paymentIds.add(entry.getPaymentId())) {
                throw inconsistentData();
            }
        }

        // Load only the payments referenced by the authorized page.
        Map<String, Payment> payments = new HashMap<>();

        if (!paymentIds.isEmpty()) {
            for (Payment payment : paymentRepository.findAllById(paymentIds)) {
                if (payment == null
                        || !paymentIds.contains(payment.getId())
                        || payments.putIfAbsent(
                                payment.getId(), payment) != null) {

                    throw inconsistentData();
                }
            }
        }

        Page<ItemizedEarningsResponse> result =
                recognitionEvents.map(entry ->
                        toResponse(entry, payments.get(entry.getPaymentId())));

        return new ItemizedEarningsPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Validates provider ownership and recognition event integrity.
     */
    private void validateRecognition(
            EarningsLedgerEntry entry,
            String providerId) {

        if (entry == null
                || !providerId.equals(entry.getProviderId())
                || !SUPPORTED_CURRENCY.equals(entry.getCurrency())
                || entry.getEventType() != EarningsLedgerEventType.EARNING_PENDING
                || entry.getPaymentId() == null
                || entry.getPaymentId().isBlank()
                || !entry.getPaymentId().equals(entry.getPaymentId().trim())
                || !(PENDING_EVENT_PREFIX + entry.getPaymentId())
                        .equals(entry.getEventKey())
                || entry.getBookingId() == null
                || entry.getBookingId().isBlank()
                || entry.getCreatedAt() == null
                || (entry.getSequenceNumber() != null
                        && entry.getSequenceNumber() <= 0)) {

            throw inconsistentData();
        }

        try {
            entry.validate();
        } catch (IllegalArgumentException | IllegalStateException
                | NullPointerException exception) {
            throw inconsistentData();
        }
    }

    /**
     * Calculates the remaining financial entitlement after successful
     * refunds without treating balance transfers as additional income.
     */
    private ItemizedEarningsResponse toResponse(
            EarningsLedgerEntry entry,
            Payment payment) {

        if (payment == null
                || !entry.getPaymentId().equals(payment.getId())
                || !entry.getBookingId().equals(payment.getBookingId())
                || !SUPPORTED_CURRENCY.equals(payment.getCurrency())
                || payment.getStatus() == null
                || !RECOGNIZED_PAYMENT_STATUSES.contains(payment.getStatus())
                || payment.getAmount() == null
                || payment.getCommissionAmount() == null) {

            throw inconsistentData();
        }

        BigDecimal refundedAmount = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO
                : payment.getRefundedAmount();

        BigDecimal netEarnings;

        try {
            netEarnings = refundCalculator.calculateRemainingProviderEarnings(
                    payment.getAmount(),
                    payment.getCommissionAmount(),
                    refundedAmount);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw inconsistentData();
        }

        BigDecimal originalEntitlement = payment.getAmount()
                .subtract(payment.getCommissionAmount());

        BigDecimal refundAdjustment = originalEntitlement
                .subtract(netEarnings);

        // Recognition may already include refunds completed before
        // earnings recognition. Later refunds cannot increase entitlement.
        if (entry.getAmount().compareTo(originalEntitlement) > 0
                || netEarnings.compareTo(entry.getAmount()) > 0
                || refundAdjustment.signum() < 0) {

            throw inconsistentData();
        }

        return new ItemizedEarningsResponse(
                payment.getId(),
                payment.getBookingId(),
                payment.getAmount(),
                payment.getCommissionRate(),
                payment.getCommissionAmount(),
                refundedAmount,
                refundAdjustment,
                netEarnings,
                payment.getCurrency(),
                entry.getCreatedAt());
    }

    /**
     * Returns a generic error without exposing internal financial records.
     */
    private ApiException inconsistentData() {
        return new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "EARNINGS_DATA_INCONSISTENT",
                "Itemized earnings are temporarily unavailable");
    }
}
