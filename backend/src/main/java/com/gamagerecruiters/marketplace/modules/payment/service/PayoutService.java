package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

/** Orchestrates manual payout requests and trusted lifecycle actions. */
@Service
public class PayoutService {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;

    private final PayoutRepository payoutRepository;
    private final ProviderProfileRepository providerProfileRepository;
    private final PayoutTransactionService payoutTransactionService;

    public PayoutService(
            PayoutRepository payoutRepository,
            ProviderProfileRepository providerProfileRepository,
            PayoutTransactionService payoutTransactionService) {
        this.payoutRepository = Objects.requireNonNull(payoutRepository);
        this.providerProfileRepository = Objects.requireNonNull(providerProfileRepository);
        this.payoutTransactionService = Objects.requireNonNull(payoutTransactionService);
    }

    /** Creates an LKR payout using the authenticated provider user ID. */
    public Payout requestPayout(String authenticatedUserId, String requestKey, BigDecimal amount) {
        String providerId = resolveFinancialProviderId(authenticatedUserId);
        Payout payout = new Payout(
                requireText(requestKey, "Request key"),
                providerId,
                amount,
                Payout.SUPPORTED_CURRENCY);
        return payoutTransactionService.reserve(payout);
    }

    /** Retrieves paginated payout history for the authenticated provider. */
    public Page<Payout> getMyPayoutHistory(String authenticatedUserId, int page, int size) {
        Pageable pageable = payoutPage(page, size);
        String providerId = resolveFinancialProviderId(authenticatedUserId);
        return payoutRepository.findByProviderId(providerId, pageable);
    }

    /** Retrieves one payout only when it belongs to the authenticated provider. */
    public Payout getMyPayout(String authenticatedUserId, String payoutId) {
        String providerId = resolveFinancialProviderId(authenticatedUserId);
        Payout payout = requirePayout(payoutId);
        if (!providerId.equals(payout.getProviderId())) {
            throw new IllegalStateException("Payout is not owned by the authenticated provider");
        }
        return payout;
    }

    /** Retrieves paginated payout history for authorized admin views. */
    public Page<Payout> getAdminPayoutHistory(int page, int size) {
        return payoutRepository.findAll(payoutPage(page, size));
    }

    /** Retrieves one payout for an authorized admin. */
    public Payout getAdminPayout(String payoutId) {
        return requirePayout(payoutId);
    }

    /** Starts external bank-transfer processing with a trusted admin ID. */
    public Payout markProcessing(String payoutId, String adminId) {
        return payoutTransactionService.markProcessing(
                requireText(payoutId, "Payout ID"),
                requireText(adminId, "Admin ID"));
    }

    /** Records explicit admin confirmation that an external transfer succeeded. */
    public Payout markPaid(String payoutId, String adminId, String transferReference) {
        return payoutTransactionService.markPaid(
                requireText(payoutId, "Payout ID"),
                requireText(adminId, "Admin ID"),
                requireText(transferReference, "Bank transfer reference"));
    }

    /** Records an external transfer failure and releases all reservations. */
    public Payout markFailed(String payoutId, String adminId, String reason) {
        return payoutTransactionService.markFailed(
                requireText(payoutId, "Payout ID"),
                requireText(adminId, "Admin ID"),
                requireText(reason, "Failure reason"));
    }

    /**
     * Resolves the financial provider ID using the authenticated user ID.
     * Provider profile existence is verified without using its document ID
     * as the booking and earnings ownership identifier.
     */
    private String resolveFinancialProviderId(String authenticatedUserId) {
        String userId = requireText(authenticatedUserId, "Authenticated user ID");
        providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"));
        return userId;
    }

    private Payout requirePayout(String payoutId) {
        return payoutRepository.findById(requireText(payoutId, "Payout ID"))
                .orElseThrow(() -> new IllegalStateException("Payout was not found"));
    }

    private static Pageable payoutPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page number must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id"));
        return PageRequest.of(page, size, sort);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
