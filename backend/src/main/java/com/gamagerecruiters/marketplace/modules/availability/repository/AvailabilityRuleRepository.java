package com.gamagerecruiters.marketplace.modules.availability.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;

public interface AvailabilityRuleRepository extends MongoRepository<AvailabilityRule, String> {

    Optional<AvailabilityRule> findByProviderId(String providerId);
}
