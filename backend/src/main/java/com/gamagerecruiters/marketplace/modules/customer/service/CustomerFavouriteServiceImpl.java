package com.gamagerecruiters.marketplace.modules.customer.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerFavourite;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouriteResponse;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerFavouriteRepository;

@Service
public class CustomerFavouriteServiceImpl implements CustomerFavouriteService {

    private static final Logger log = LoggerFactory.getLogger(CustomerFavouriteServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 6;
    private static final int MAX_PAGE_SIZE = 50;

    private final CustomerFavouriteRepository customerFavouriteRepository;
    private final FavouriteProviderLookup favouriteProviderLookup;
    private final ObjectProvider<FavouriteListingLookup> listingLookupProvider;

    public CustomerFavouriteServiceImpl(
            CustomerFavouriteRepository customerFavouriteRepository,
            FavouriteProviderLookup favouriteProviderLookup,
            ObjectProvider<FavouriteListingLookup> listingLookupProvider
    ) {
        this.customerFavouriteRepository = customerFavouriteRepository;
        this.favouriteProviderLookup = favouriteProviderLookup;
        this.listingLookupProvider = listingLookupProvider;
    }

    @Override
    public CustomerFavouritePageResponse getFavourites(String customerId, FavouriteType type, int page, int size) {
        validateType(type);

        if (type == FavouriteType.LISTING) {
            requireListingLookup();
        }

        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pageNumber = Math.max(0, page);

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CustomerFavourite> favouritePage = customerFavouriteRepository.findByCustomerIdAndTypeOrderByCreatedAtDesc(
                customerId, type, pageable
        );

        List<CustomerFavourite> favourites = favouritePage.getContent();
        if (favourites.isEmpty()) {
            return new CustomerFavouritePageResponse(
                    Collections.emptyList(),
                    favouritePage.getNumber(),
                    favouritePage.getSize(),
                    favouritePage.getTotalElements(),
                    favouritePage.getTotalPages()
            );
        }

        List<String> targetIds = favourites.stream()
                .map(CustomerFavourite::getTargetId)
                .toList();

        Map<String, FavouriteTargetDetails> detailsMap = resolveTargetDetailsBatch(type, targetIds);

        List<CustomerFavouriteResponse> content = new ArrayList<>();
        for (CustomerFavourite fav : favourites) {
            FavouriteTargetDetails details = detailsMap.get(fav.getTargetId());
            if (details == null) {
                // Silently skip targets that no longer exist (e.g. deleted provider/listing)
                continue;
            }

            content.add(new CustomerFavouriteResponse(
                    fav.getId(),
                    fav.getType(),
                    fav.getTargetId(),
                    details.name(),
                    details.subtitle(),
                    details.category(),
                    details.city(),
                    details.imageUrl(),
                    details.rating(),
                    details.reviewCount(),
                    details.priceFrom(),
                    details.priceUnit(),
                    details.currency(),
                    details.inactive(),
                    fav.getCreatedAt()
            ));
        }

        return new CustomerFavouritePageResponse(
                content,
                favouritePage.getNumber(),
                favouritePage.getSize(),
                favouritePage.getTotalElements(),
                favouritePage.getTotalPages()
        );
    }

    @Override
    public CustomerFavouriteResponse addFavourite(String customerId, FavouriteType type, String targetId) {
        validateType(type);
        validateTargetId(targetId);

        // Verify target exists
        FavouriteTargetDetails details;
        if (type == FavouriteType.PROVIDER) {
            Optional<FavouriteTargetDetails> provider = favouriteProviderLookup.lookup(targetId);
            if (provider.isEmpty()) {
                throw new ResourceNotFoundException("TARGET_NOT_FOUND", "Provider not found with targetId: " + targetId);
            }
            details = provider.get();
        } else if (type == FavouriteType.LISTING) {
            FavouriteListingLookup listingLookup = requireListingLookup();
            Optional<FavouriteTargetDetails> listing = listingLookup.lookup(targetId);
            if (listing.isEmpty()) {
                throw new ResourceNotFoundException("TARGET_NOT_FOUND", "Listing not found with targetId: " + targetId);
            }
            details = listing.get();
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FAVOURITE_TYPE", "Invalid favourite type: " + type);
        }

        CustomerFavourite favourite;
        Optional<CustomerFavourite> existing = customerFavouriteRepository.findByCustomerIdAndTypeAndTargetId(customerId, type, targetId);
        if (existing.isPresent()) {
            favourite = existing.get();
        } else {
            try {
                favourite = customerFavouriteRepository.save(new CustomerFavourite(customerId, type, targetId, Instant.now()));
            } catch (DuplicateKeyException ex) {
                log.debug("Concurrent duplicate favourite add treated as idempotent success: customerId={}, type={}, targetId={}",
                        customerId, type, targetId);
                favourite = customerFavouriteRepository.findByCustomerIdAndTypeAndTargetId(customerId, type, targetId)
                        .orElse(new CustomerFavourite(customerId, type, targetId, Instant.now()));
            }
        }

        return new CustomerFavouriteResponse(
                favourite.getId(),
                favourite.getType(),
                favourite.getTargetId(),
                details.name(),
                details.subtitle(),
                details.category(),
                details.city(),
                details.imageUrl(),
                details.rating(),
                details.reviewCount(),
                details.priceFrom(),
                details.priceUnit(),
                details.currency(),
                details.inactive(),
                favourite.getCreatedAt()
        );
    }

    @Override
    public void removeFavourite(String customerId, FavouriteType type, String targetId) {
        validateType(type);
        validateTargetId(targetId);

        // Idempotent deletion
        customerFavouriteRepository.deleteByCustomerIdAndTypeAndTargetId(customerId, type, targetId);
    }

    private FavouriteListingLookup requireListingLookup() {
        FavouriteListingLookup lookup = listingLookupProvider.getIfAvailable();
        if (lookup == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FAVOURITES_LISTING_UNAVAILABLE",
                    "Listing favourites are temporarily unavailable"
            );
        }
        return lookup;
    }

    private Map<String, FavouriteTargetDetails> resolveTargetDetailsBatch(FavouriteType type, List<String> targetIds) {
        if (type == FavouriteType.PROVIDER) {
            return favouriteProviderLookup.lookupBatch(targetIds);
        } else if (type == FavouriteType.LISTING) {
            FavouriteListingLookup lookup = requireListingLookup();
            return lookup.lookupBatch(targetIds);
        }
        return Collections.emptyMap();
    }

    private void validateType(FavouriteType type) {
        if (type == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FAVOURITE_TYPE",
                    "Favourite type is required and must be PROVIDER or LISTING"
            );
        }
    }

    private void validateTargetId(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TARGET_ID",
                    "Target ID is required"
            );
        }
    }
}
