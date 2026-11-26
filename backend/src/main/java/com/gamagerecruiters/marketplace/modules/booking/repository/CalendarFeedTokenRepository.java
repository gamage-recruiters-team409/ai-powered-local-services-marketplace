package com.gamagerecruiters.marketplace.modules.booking.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.booking.domain.CalendarFeedToken;

public interface CalendarFeedTokenRepository extends MongoRepository<CalendarFeedToken, String> {

    Optional<CalendarFeedToken> findByTokenHash(String tokenHash);

    Optional<CalendarFeedToken> findByUserId(String userId);

    void deleteByUserId(String userId);
}
