
package com.gamagerecruiters.marketplace.modules.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderEarningsSummaryService;

/**
 * Read-only REST endpoints for authenticated provider earnings.
 *
 * <p>
 * Access is restricted to users with the PROVIDER role.
 * Provider identity is resolved from the security context
 * rather than accepted from client-controlled input.
 * </p>
 *
 * <p>
 * These endpoints expose calculated earnings summaries only.
 * They do not authorize payouts or modify ledger records.
 * </p>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/provider/earnings")
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderEarningsController {

    private static final String SUPPORTED_CURRENCY = "LKR";

    private final ProviderEarningsSummaryService summaryService;

    /**
     * Creates the provider earnings controller.
     *
     * @param summaryService read-only earnings summary service
     */
    public ProviderEarningsController(
            ProviderEarningsSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * Retrieves the authenticated provider's LKR earnings summary.
     *
     * @return pending, available, held and paid-out balances
     */
    @GetMapping("/me")
    public ResponseEntity<EarningsSummaryResponse> getMyEarnings() {

        String providerId = requireAuthenticatedProviderId();

        EarningsSummaryResponse response = summaryService.getSummary(
                providerId,
                SUPPORTED_CURRENCY);

        return ResponseEntity.ok(response);
    }

    /**
     * Resolves the authenticated provider ID.
     *
     * @return trusted provider identifier
     * @throws ApiException when authentication is missing
     */
    private String requireAuthenticatedProviderId() {

        String providerId = CurrentUser.getUserId();

        if (providerId == null || providerId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access earnings");
        }

        return providerId.trim();
    }
}
