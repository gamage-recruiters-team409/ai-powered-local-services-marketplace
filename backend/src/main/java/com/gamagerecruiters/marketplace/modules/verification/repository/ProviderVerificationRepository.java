
package com.gamagerecruiters.marketplace.modules.verification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

public interface ProviderVerificationRepository
        extends MongoRepository<ProviderVerification, String> {

    Optional<ProviderVerification> findByProviderId(String providerId);

    List<ProviderVerification> findAllByProviderIdIn(Collection<String> providerIds);

    List<ProviderVerification> findAllByStatusInOrderBySubmittedAtAsc(
            Collection<ProviderVerificationStatus> statuses
    );

    @Query(value = "{ 'status': ?0 }", fields = "{ 'providerId': 1, '_id': 0 }")
    List<ProviderIdOnly> findProviderIdsByStatus(ProviderVerificationStatus status);

    interface ProviderIdOnly {
        String getProviderId();
    }
}
