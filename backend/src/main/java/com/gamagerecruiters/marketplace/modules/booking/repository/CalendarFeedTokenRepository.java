package com.gamagerecruiters.marketplace.modules.booking.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import com.gamagerecruiters.marketplace.modules.booking.domain.CalendarFeedToken;

public interface CalendarFeedTokenRepository extends MongoRepository<CalendarFeedToken, String> {

    Optional<CalendarFeedToken> findByTokenHash(String tokenHash);

    Optional<CalendarFeedToken> findByUserId(String userId);

    void deleteByUserId(String userId);

    /**
     * Sets only {@code lastUsedAt} on the token that is still stored with this id AND hash. A plain update, never
     * an upsert: if the link was revoked or reset meanwhile, nothing matches and nothing is (re)created.
     * Returns the number of updated documents (0 or 1).
     */
    @Query("{ '_id': ?0, 'tokenHash': ?1 }")
    @Update("{ '$set': { 'lastUsedAt': ?2 } }")
    long touchLastUsed(String id, String tokenHash, Instant lastUsedAt);
}
