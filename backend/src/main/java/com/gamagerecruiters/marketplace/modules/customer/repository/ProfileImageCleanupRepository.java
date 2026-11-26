package com.gamagerecruiters.marketplace.modules.customer.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupStatus;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;

@Repository
public interface ProfileImageCleanupRepository extends MongoRepository<ProfileImageCleanupTask, String> {

    Optional<ProfileImageCleanupTask> findByPublicId(String publicId);

    Optional<ProfileImageCleanupTask> findByPublicIdAndStatus(String publicId, ProfileImageCleanupStatus status);

    List<ProfileImageCleanupTask> findByStatusOrderByCreatedAtAsc(ProfileImageCleanupStatus status, Pageable pageable);

    void deleteByPublicId(String publicId);

    void deleteByPublicIdAndStatus(String publicId, ProfileImageCleanupStatus status);
}
