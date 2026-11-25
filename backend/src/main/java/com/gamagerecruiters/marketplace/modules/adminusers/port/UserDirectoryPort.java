package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;

/**
 * User accounts (WP01, auth module). The real adapter uses UserInternalService; parts the source has no method for
 * answer "not available" (empty Optional), never a guessed value.
 *
 * <p>{@link UserAccount} carries only display-safe fields: never a password hash, token, reset code or security
 * counter. {@code lastActiveAt} is the last sign-in, null when the account has none recorded.</p>
 */
public interface UserDirectoryPort {

    record UserAccount(String id, String displayName, String email, String phone, Role role, UserStatus status,
                       boolean emailVerified, Instant joinedAt, Instant lastActiveAt) {

        public UserAccount(String id, String displayName, String email, String phone, Role role, UserStatus status,
                           boolean emailVerified, Instant joinedAt) {
            this(id, displayName, email, phone, role, status, emailVerified, joinedAt, null);
        }
    }

    /** One registration bucket, [start, end) in UTC. */
    record Bucket(Instant start, Instant end) {
    }

    record RegistrationCount(Instant start, Instant end, long customers, long providers) {
    }

    /** Newest joined first. {@code nameOrEmail} is plain text (escaped by the source). */
    Page<UserAccount> search(String actorId, Set<Role> roles, UserStatus status, String nameOrEmail, Pageable pageable);

    Optional<UserAccount> findById(String userId);

    /** {@code status} null = every status. */
    long count(String actorId, Role role, UserStatus status);

    /**
     * Accounts of {@code role} that joined in [from, to); empty when the source cannot count by date. Accounts without
     * a join date are not counted.
     */
    Optional<Long> joinedBetween(String actorId, Role role, Instant from, Instant to);

    /**
     * Customers and providers that joined in each bucket (consecutive weeks starting Monday, or months starting on the
     * 1st, UTC); a bucket with no registrations is 0. Empty when the source cannot count by date.
     */
    Optional<List<RegistrationCount>> registrations(String actorId, Granularity granularity, List<Bucket> buckets);

    /** False when suspend/reactivate cannot be applied (stub): the API answers 503 and changes nothing. */
    boolean supportsAccountActions();

    /** Error code when the account's status is no longer {@code expectedStatus} (409, from the auth module). */
    String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    /**
     * Applies {@code newStatus} through the source's own rules, atomically and only while the account is still in
     * {@code expectedStatus} (the status the caller read and checked its rules against). When another change got
     * there first: 409 {@value #CONCURRENT_MODIFICATION}, nothing changed. {@code actorId} is the signed-in admin.
     * The source's other errors pass through unchanged.
     */
    UserAccount changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus);
}
