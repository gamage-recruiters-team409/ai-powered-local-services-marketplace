package com.gamagerecruiters.marketplace.modules.listing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;

/**
 * Internal MongoDB repository for {@link ServiceListing}. Other modules must call {@code ListingService}.
 */
public interface ListingRepository extends MongoRepository<ServiceListing, String> {

    List<ServiceListing> findByProviderIdOrderByUpdatedAtDesc(String providerId);

    Page<ServiceListing> findByProviderIdOrderByUpdatedAtDesc(String providerId, Pageable pageable);

    Page<ServiceListing> findByProviderIdAndStatusOrderByUpdatedAtDesc(
            String providerId,
            ListingStatus status,
            Pageable pageable
    );

    Page<ServiceListing> findByProviderIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
            String providerId,
            String title,
            Pageable pageable
    );

    Page<ServiceListing> findByProviderIdAndStatusAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
            String providerId,
            ListingStatus status,
            String title,
            Pageable pageable
    );

    List<ServiceListing> findByProviderIdAndStatus(String providerId, ListingStatus status);

    List<ServiceListing> findByProviderIdAndHiddenByProviderSuspensionTrue(String providerId);

    Page<ServiceListing> findByStatusOrderByCreatedAtAsc(ListingStatus status, Pageable pageable);

    boolean existsByCategoryIdAndStatusIn(String categoryId, Collection<ListingStatus> statuses);

    List<ServiceListing> findByStatusAndLocationNear(
            ListingStatus status,
            Point location,
            Distance distance
    );
}
