
package com.gamagerecruiters.marketplace.modules.verification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

public interface ProviderVerificationRepository
        extends MongoRepository<ProviderVerification, String> {

    Optional<ProviderVerification> findByProviderId(String providerId);

    List<ProviderVerification> findAllByStatusInOrderBySubmittedAtAsc(
            Collection<ProviderVerificationStatus> statuses
    );
}
