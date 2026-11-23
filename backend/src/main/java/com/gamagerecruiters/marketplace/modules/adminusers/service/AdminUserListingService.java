package com.gamagerecruiters.marketplace.modules.adminusers.service;

import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingDetail;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingRow;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * A provider's listings on the account page, READ-ONLY for every role: the paginated "Listings" section and the
 * "View listing details" modal. There is no admin listing decision here; approving / rejecting flagged listings stays
 * in the listing module's MODERATOR workflow.
 * <ul>
 *   <li>Unknown user, a customer, or an admin account: 404 USER_NOT_FOUND / LISTING_NOT_FOUND.</li>
 *   <li>A listing that does not exist or is not this provider's: 404 LISTING_NOT_FOUND (the listing id is never
 *       echoed).</li>
 * </ul>
 */
@Service
public class AdminUserListingService {

    static final int MAX_LISTING_ID_LENGTH = 64;

    private final AdminUsersQueryService queryService;
    private final ProviderListingPort listings;

    public AdminUserListingService(AdminUsersQueryService queryService, ProviderListingPort listings) {
        this.queryService = queryService;
        this.listings = listings;
    }

    /** One page of the provider's listings (every status, most recently updated first). */
    public Section<AdminPageResponse<ListingRow>> page(String userId, int page, int size) {
        if (page < 0 || size < 1 || size > AdminUsersRules.MAX_LISTINGS_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST",
                    "page must be 0 or more and size must be between 1 and " + AdminUsersRules.MAX_LISTINGS_PAGE_SIZE);
        }
        UserAccount provider = provider(userId);
        return queryService.guarded(provider.id(), "listingsPage",
                () -> AdminPageResponse.from(listings.listings(provider.id(), PageRequest.of(page, size))));
    }

    public Section<ListingDetail> detail(String userId, String listingId) {
        UserAccount provider = provider(userId);
        return find(provider, listingId).map(Section::of).orElseGet(Section::notConnected);
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

    static ResourceNotFoundException listingNotFound() {
        return new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found");
    }
}
