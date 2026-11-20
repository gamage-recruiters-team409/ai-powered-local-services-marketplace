package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;

/**
 * Adapter implementing {@link FavouriteProviderLookup} using the provider and auth modules (FR-CUST-005).
 * Resolves provider existence, display name, city/district, profile image, and active/suspended status.
 * Rating, review count and starting price remain null as those fields do not exist on ProviderProfile.
 * Private provider data (e.g. email, phone) is never exposed.
 */
@Component
public class FavouriteProviderLookupAdapter implements FavouriteProviderLookup {

    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;

    public FavouriteProviderLookupAdapter(MongoTemplate mongoTemplate, UserRepository userRepository) {
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
    }

    @Override
    public Optional<FavouriteTargetDetails> lookup(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return Optional.empty();
        }
        Map<String, FavouriteTargetDetails> batch = lookupBatch(List.of(targetId));
        return Optional.ofNullable(batch.get(targetId));
    }

    @Override
    public Map<String, FavouriteTargetDetails> lookupBatch(Collection<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // Query ProviderProfile by _id or userId to support either identifier
        Query query = Query.query(new Criteria().orOperator(
                Criteria.where("_id").in(targetIds),
                Criteria.where("userId").in(targetIds)
        ));
        List<ProviderProfile> profiles = mongoTemplate.find(query, ProviderProfile.class);

        if (profiles.isEmpty()) {
            return Collections.emptyMap();
        }

        // Batch fetch users to check suspended / active status
        List<String> userIds = profiles.stream()
                .map(ProviderProfile::getUserId)
                .filter(uid -> uid != null && !uid.isBlank())
                .toList();

        Map<String, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userRepository.findAllById(userIds);
            for (User u : users) {
                userMap.put(u.getId(), u);
            }
        }

        Map<String, FavouriteTargetDetails> result = new HashMap<>();
        for (ProviderProfile profile : profiles) {
            User user = userMap.get(profile.getUserId());
            boolean inactive = user != null && user.getStatus() == UserStatus.SUSPENDED;
            if (user != null && user.getStatus() != UserStatus.ACTIVE) {
                inactive = true;
            }

            // Location: city preferred, fallback to district
            String location = profile.getCity() != null && !profile.getCity().isBlank()
                    ? profile.getCity()
                    : profile.getDistrict();

            FavouriteTargetDetails details = new FavouriteTargetDetails(
                    profile.getId(),
                    profile.getDisplayName(),
                    null,
                    null,
                    location,
                    profile.getProfileImageUrl(),
                    null, // rating not on ProviderProfile
                    null, // reviewCount not on ProviderProfile
                    null, // priceFrom not on ProviderProfile
                    null,
                    null,
                    inactive
            );

            // Index by both profile ID and userId so any supplied targetId resolves
            if (profile.getId() != null) {
                result.put(profile.getId(), details);
            }
            if (profile.getUserId() != null) {
                result.put(profile.getUserId(), details);
            }
        }

        return result;
    }
}
