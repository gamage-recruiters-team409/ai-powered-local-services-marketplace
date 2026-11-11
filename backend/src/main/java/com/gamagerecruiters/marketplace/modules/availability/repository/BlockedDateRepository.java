package com.gamagerecruiters.marketplace.modules.availability.repository;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;

public interface BlockedDateRepository extends MongoRepository<BlockedDate, String> {

    boolean existsByProviderIdAndDate(String providerId, String date);

    /**
     * Dates are "yyyy-MM-dd" strings, so string comparison gives the correct date order.
     * Both ends of the range are inclusive.
     */
    @Query("{ 'providerId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<BlockedDate> findInRange(String providerId, String fromDate, String toDate, Sort sort);
}
