package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminErrors;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * UserDirectoryPort through the auth module's UserInternalService only (searchUsers, getById, countByRoleAndStatus,
 * countNewUsers and the atomic 4-argument changeStatus). Only the fields of {@link AdminUserSummary} are copied out
 * (plus emailVerified): never passwordHash, lock or login counters.
 */
@Component
public class AuthUserDirectoryAdapter implements UserDirectoryPort {

    private final UserInternalService userInternalService;

    public AuthUserDirectoryAdapter(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    @Override
    public Page<UserAccount> search(String actorId, Set<Role> roles, UserStatus status, String nameOrEmail,
                                    Pageable pageable) {
        return userInternalService.searchUsers(actorId, roles, status, nameOrEmail, pageable)
                .map(s -> fromSummary(s, false));
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        try {
            var user = userInternalService.getById(userId);
            return Optional.of(fromSummary(AdminUserSummary.fromUser(user), user.isEmailVerified()));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public long count(String actorId, Role role, UserStatus status) {
        return userInternalService.countByRoleAndStatus(actorId, role, status);
    }

    /** Sum of countNewUsers' periods: it only counts createdAt in [from, to), so the bucket edges do not matter. */
    @Override
    public Optional<Long> joinedBetween(String actorId, Role role, Instant from, Instant to) {
        return Optional.of(userInternalService.countNewUsers(actorId, Set.of(role), from, to, Granularity.WEEK)
                .stream().mapToLong(PeriodCount::count).sum());
    }

    /** One countNewUsers call per role over the whole range; each bucket takes the period that starts with it. */
    @Override
    public Optional<List<RegistrationCount>> registrations(String actorId, Granularity granularity,
                                                           List<Bucket> buckets) {
        if (buckets.isEmpty()) {
            return Optional.of(List.of());
        }
        Instant from = buckets.get(0).start();
        Instant to = buckets.get(buckets.size() - 1).end();
        Map<Instant, Long> customers = byPeriod(actorId, Role.CUSTOMER, from, to, granularity);
        Map<Instant, Long> providers = byPeriod(actorId, Role.PROVIDER, from, to, granularity);
        List<RegistrationCount> result = new ArrayList<>(buckets.size());
        for (Bucket b : buckets) {
            result.add(new RegistrationCount(b.start(), b.end(), customers.getOrDefault(b.start(), 0L),
                    providers.getOrDefault(b.start(), 0L)));
        }
        return Optional.of(result);
    }

    private Map<Instant, Long> byPeriod(String actorId, Role role, Instant from, Instant to, Granularity granularity) {
        Map<Instant, Long> counts = new HashMap<>();
        for (PeriodCount p : userInternalService.countNewUsers(actorId, Set.of(role), from, to, granularity)) {
            counts.merge(p.periodStart(), p.count(), Long::sum);
        }
        return counts;
    }

    @Override
    public boolean supportsAccountActions() {
        return true;
    }

    /**
     * One atomic call: auth applies its own rules and changes the status only while it is still
     * {@code expectedStatus}, revoking refresh tokens on suspend. Its 409 CONCURRENT_MODIFICATION keeps the code with
     * the admin-facing message; every other error (404 USER_NOT_FOUND, CANNOT_MODIFY_SELF, ROLE_HIERARCHY_VIOLATION,
     * ACCOUNT_DEACTIVATED, ...) passes through unchanged.
     */
    @Override
    public UserAccount changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus) {
        try {
            var user = userInternalService.changeStatus(actorId, userId, expectedStatus, newStatus);
            return fromSummary(AdminUserSummary.fromUser(user), user.isEmailVerified());
        } catch (RuntimeException e) {
            throw AdminErrors.fromStatusChange(e);
        }
    }

    /** The search result has no email-verified flag; it is only needed on the account page (findById). */
    private static UserAccount fromSummary(AdminUserSummary s, boolean emailVerified) {
        return new UserAccount(s.id(), s.fullName(), s.email(), s.phone(), s.role(), s.status(), emailVerified,
                s.createdAt(), s.lastActiveAt());
    }
}
