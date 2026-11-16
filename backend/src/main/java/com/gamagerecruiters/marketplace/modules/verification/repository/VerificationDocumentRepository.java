package com.gamagerecruiters.marketplace.modules.verification.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;

public interface VerificationDocumentRepository
        extends MongoRepository<VerificationDocument, String> {

    List<VerificationDocument> findAllByProviderIdOrderByUploadedAtDesc(
            String providerId
    );

    Optional<VerificationDocument> findByIdAndProviderId(
            String id,
            String providerId
    );
}