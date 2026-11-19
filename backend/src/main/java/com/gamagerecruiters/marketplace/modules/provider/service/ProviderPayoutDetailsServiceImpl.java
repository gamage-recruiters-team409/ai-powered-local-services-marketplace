package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.provider.crypto.ProviderPayoutEncryptionService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderPayoutDetailsResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderPayoutDetailsRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

@Service
public class ProviderPayoutDetailsServiceImpl
        implements ProviderPayoutDetailsService {

    private static final int VISIBLE_ACCOUNT_NUMBER_DIGITS = 4;
    private static final int MIN_ACCOUNT_NUMBER_DIGITS = 6;
    private static final int MAX_ACCOUNT_NUMBER_DIGITS = 34;

    private final ProviderProfileRepository providerProfileRepository;
    private final ProviderPayoutEncryptionService encryptionService;

    public ProviderPayoutDetailsServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            ProviderPayoutEncryptionService encryptionService
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.encryptionService = encryptionService;
    }

    @Override
    public ProviderPayoutDetailsResponse getMyPayoutDetails() {
        String userId = requireCurrentUserId();
        ProviderProfile profile = requireProviderProfile(userId);

        return toProviderResponse(profile);
    }

    @Override
    public ProviderPayoutDetailsResponse updateMyPayoutDetails(
            UpdateProviderPayoutDetailsRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payout details request is required"
            );
        }

        String userId = requireCurrentUserId();
        ProviderProfile profile = requireProviderProfile(userId);

        String bankName = requireText(
                request.getBankName(),
                "Bank name is required"
        );

        String accountHolderName = requireText(
                request.getAccountHolderName(),
                "Account holder name is required"
        );

        String accountNumber = normalizeAccountNumber(
                request.getAccountNumber()
        );

        // Encrypt before the account number is persisted in MongoDB.
        String encryptedAccountNumber =
                encryptionService.encrypt(accountNumber);

        profile.setPayoutBankName(bankName);
        profile.setPayoutAccountHolderName(accountHolderName);
        profile.setPayoutAccountNumberEncrypted(encryptedAccountNumber);
        profile.setUpdatedAt(Instant.now());

        ProviderProfile savedProfile =
                providerProfileRepository.save(profile);

        // Use the already-normalized value to build the masked response.
        return toProviderResponse(savedProfile, accountNumber);
    }

    /**
     * Internal read-only integration contract for WP07.
     * This method accepts the provider USER ID, not the profile document ID.
     */
    @Override
    public Optional<ProviderPayoutDetails> getPayoutDetailsForPayoutProcessing(
            String providerUserId
    ) {
        if (providerUserId == null || providerUserId.isBlank()) {
            return Optional.empty();
        }

        return providerProfileRepository
                .findByUserId(providerUserId.trim())
                .filter(this::hasCompletePayoutDetails)
                .map(profile -> {
                    String accountNumber = encryptionService.decrypt(
                            profile.getPayoutAccountNumberEncrypted()
                    );

                    return new ProviderPayoutDetails(
                            profile.getPayoutBankName(),
                            profile.getPayoutAccountHolderName(),
                            accountNumber
                    );
                });
    }

    private ProviderPayoutDetailsResponse toProviderResponse(
            ProviderProfile profile
    ) {
        if (!hasCompletePayoutDetails(profile)) {
            return new ProviderPayoutDetailsResponse(
                    null,
                    null,
                    null,
                    false,
                    profile.getUpdatedAt()
            );
        }

        String accountNumber = encryptionService.decrypt(
                profile.getPayoutAccountNumberEncrypted()
        );

        return toProviderResponse(profile, accountNumber);
    }

    private ProviderPayoutDetailsResponse toProviderResponse(
            ProviderProfile profile,
            String accountNumber
    ) {
        return new ProviderPayoutDetailsResponse(
                profile.getPayoutBankName(),
                profile.getPayoutAccountHolderName(),
                maskAccountNumber(accountNumber),
                true,
                profile.getUpdatedAt()
        );
    }

    private boolean hasCompletePayoutDetails(ProviderProfile profile) {
        return profile != null
                && hasText(profile.getPayoutBankName())
                && hasText(profile.getPayoutAccountHolderName())
                && hasText(profile.getPayoutAccountNumberEncrypted());
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }

        int visibleDigits = Math.min(
                VISIBLE_ACCOUNT_NUMBER_DIGITS,
                accountNumber.length()
        );

        int maskedLength = accountNumber.length() - visibleDigits;

        return "*".repeat(maskedLength)
                + accountNumber.substring(maskedLength);
    }

    private String normalizeAccountNumber(String accountNumber) {
        if (!hasText(accountNumber)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Account number is required"
            );
        }

        // Spaces and hyphens are accepted in the request but not stored.
        String normalized = accountNumber.replaceAll("[\\s-]", "");

        if (!normalized.matches("\\d{" + MIN_ACCOUNT_NUMBER_DIGITS
                + "," + MAX_ACCOUNT_NUMBER_DIGITS + "}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Account number must contain between "
                            + MIN_ACCOUNT_NUMBER_DIGITS
                            + " and "
                            + MAX_ACCOUNT_NUMBER_DIGITS
                            + " digits"
            );
        }

        return normalized;
    }

    private String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    message
            );
        }

        return value.trim();
    }

    private ProviderProfile requireProviderProfile(String providerUserId) {
        return providerProfileRepository.findByUserId(providerUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));
    }

    private String requireCurrentUserId() {
        String userId = CurrentUser.getUserId();

        if (!hasText(userId)) {
            throw new AccessDeniedException("Authentication required");
        }

        return userId;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
