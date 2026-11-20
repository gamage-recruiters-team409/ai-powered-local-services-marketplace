package com.gamagerecruiters.marketplace.modules.adminusers.service;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingDetail;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ReviewDecision;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * A provider's hidden listing on the account page: the review modal's detail and the admin's decision.
 * <ul>
 *   <li>Unknown user, a customer, or an admin account: 404 USER_NOT_FOUND / LISTING_NOT_FOUND.</li>
 *   <li>A listing that does not exist or is not this provider's: 404 LISTING_NOT_FOUND (the listing id is never
 *       echoed).</li>
 *   <li>Review: decision KEEP_HIDDEN | MAKE_VISIBLE and a note of 10..500 (400 VALIDATION_FAILED); while the listing
 *       port cannot apply decisions: 503 LISTING_ACTION_UNAVAILABLE and nothing changes.</li>
 * </ul>
 * Notes are never logged.
 *
 * <p>TODO(audit): the admin audit log's actions are account actions only (ADMIN_STATUS_CHANGED); record listing
 * decisions once the review is connected and a listing audit action exists.</p>
 */
@Service
public class AdminUserListingService {

    public static final String LISTING_ACTION_UNAVAILABLE = "LISTING_ACTION_UNAVAILABLE";
    static final int MAX_LISTING_ID_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(AdminUserListingService.class);

    private final AdminUsersQueryService queryService;
    private final ProviderListingPort listings;

    public AdminUserListingService(AdminUsersQueryService queryService, ProviderListingPort listings) {
        this.queryService = queryService;
        this.listings = listings;
    }

    public Section<ListingDetail> detail(String userId, String listingId) {
        UserAccount provider = provider(userId);
        return find(provider, listingId).map(Section::of).orElseGet(Section::notConnected);
    }

    public void review(String actorId, String userId, String listingId, String decision, String note) {
        ReviewDecision parsed = parseDecision(decision);
        String cleanNote = AdminUserAccountService.clean(note);
        if (cleanNote.length() < AdminUsersRules.MIN_REASON_LENGTH
                || cleanNote.length() > AdminUsersRules.MAX_REASON_LENGTH) {
            throw AdminUserAccountService.invalid("note must be between " + AdminUsersRules.MIN_REASON_LENGTH
                    + " and " + AdminUsersRules.MAX_REASON_LENGTH + " characters");
        }
        UserAccount provider = provider(userId);
        if (!listings.supportsListingReview()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, LISTING_ACTION_UNAVAILABLE,
                    "Listing decisions are not available yet. Nothing was changed");
        }
        ListingDetail listing = find(provider, listingId).orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, LISTING_ACTION_UNAVAILABLE,
                "Listing decisions are not available yet. Nothing was changed"));
        listings.reviewListing(actorId, listing.id(), parsed, cleanNote);
        log.info("event=ADMIN_LISTING_REVIEWED userId={} listingId={} actorId={} decision={}",
                provider.id(), listing.id(), actorId, parsed);
    }

    private UserAccount provider(String userId) {
        UserAccount account = queryService.findManaged(userId);
        if (account.role() != Role.PROVIDER) {
            throw listingNotFound();
        }
        return account;
    }

    /** Empty: the source cannot provide it. Not found, or another provider's listing: 404. */
    private Optional<ListingDetail> find(UserAccount provider, String listingId) {
        if (listingId == null || listingId.isBlank() || listingId.length() > MAX_LISTING_ID_LENGTH) {
            throw listingNotFound();
        }
        Optional<ListingDetail> listing;
        try {
            listing = listings.listingDetail(listingId);
        } catch (ResourceNotFoundException e) {
            throw listingNotFound();
        }
        listing.filter(l -> !provider.id().equals(l.providerId())).ifPresent(l -> {
            throw listingNotFound();
        });
        return listing;
    }

    private static ReviewDecision parseDecision(String value) {
        try {
            return ReviewDecision.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AdminUserAccountService.invalid("decision must be KEEP_HIDDEN or MAKE_VISIBLE");
        }
    }

    static ResourceNotFoundException listingNotFound() {
        return new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found");
    }
}
