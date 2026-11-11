package com.gamagerecruiters.marketplace.modules.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

@Repository
public interface ReviewRepository extends MongoRepository<Review, String> {

    Optional<Review> findByBookingId(String bookingId);

    boolean existsByBookingId(String bookingId);

    Page<Review> findByCustomerId(String customerId, Pageable pageable);

    Page<Review> findByProviderId(String providerId, Pageable pageable);

    Page<Review> findByProviderIdAndStatus(String providerId, ReviewStatus status, Pageable pageable);

    List<Review> findByProviderIdAndStatus(String providerId, ReviewStatus status);

    long countByProviderIdAndStatus(String providerId, ReviewStatus status);
}
