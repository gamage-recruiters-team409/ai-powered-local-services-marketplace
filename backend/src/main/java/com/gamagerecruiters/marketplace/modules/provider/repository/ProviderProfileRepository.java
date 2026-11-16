package com.gamagerecruiters.marketplace.modules.provider.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;

public interface ProviderProfileRepository extends MongoRepository<ProviderProfile, String> {

    Optional<ProviderProfile> findByUserId(String userId);

    boolean existsByUserId(String userId);
}