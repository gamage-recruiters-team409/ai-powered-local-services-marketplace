package com.gamagerecruiters.marketplace.modules.customer.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerFavourite;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;

public interface CustomerFavouriteRepository extends MongoRepository<CustomerFavourite, String> {

    Optional<CustomerFavourite> findByCustomerIdAndTypeAndTargetId(String customerId, FavouriteType type, String targetId);

    boolean existsByCustomerIdAndTypeAndTargetId(String customerId, FavouriteType type, String targetId);

    Page<CustomerFavourite> findByCustomerIdAndTypeOrderByCreatedAtDesc(String customerId, FavouriteType type, Pageable pageable);

    void deleteByCustomerIdAndTypeAndTargetId(String customerId, FavouriteType type, String targetId);

    void deleteAllByCustomerId(String customerId);

    long countByCustomerIdAndType(String customerId, FavouriteType type);
}
