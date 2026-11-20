package com.gamagerecruiters.marketplace.modules.provider.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;

public interface ProviderProfileRepository extends MongoRepository<ProviderProfile, String> {

    Optional<ProviderProfile> findByUserId(String userId);

    List<ProviderProfile> findAllByUserIdIn(Collection<String> userIds);

    boolean existsByUserId(String userId);
}