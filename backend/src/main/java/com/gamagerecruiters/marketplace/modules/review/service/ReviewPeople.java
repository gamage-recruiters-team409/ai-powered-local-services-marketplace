package com.gamagerecruiters.marketplace.modules.review.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Display names for review cards (provider list S11, moderation S14/S15), read through the auth module's
 * cross-module {@link UserInternalService}. A missing or unreadable user simply has no name (null);
 * it never fails the request.
 */
@Component
public class ReviewPeople {

    private final UserInternalService userInternalService;

    public ReviewPeople(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    /** User ID -> name, for the distinct non-null IDs given. Users that cannot be read are left out. */
    public Map<String, String> namesOf(Collection<String> userIds) {
        Map<String, String> names = new HashMap<>();
        userIds.stream().filter(Objects::nonNull).distinct().forEach(id -> {
            String name = nameOf(id);
            if (name != null) {
                names.put(id, name);
            }
        });
        return names;
    }

    public String nameOf(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            User user = userInternalService.getById(userId);
            return user == null ? null : user.getName();
        } catch (RuntimeException notAvailable) {
            return null;
        }
    }
}
