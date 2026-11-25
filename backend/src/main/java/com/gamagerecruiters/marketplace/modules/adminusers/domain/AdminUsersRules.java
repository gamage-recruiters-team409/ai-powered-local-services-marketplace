package com.gamagerecruiters.marketplace.modules.adminusers.domain;

import java.util.EnumSet;
import java.util.Set;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/** Limits and allowed values of the admin users screens. */
public final class AdminUsersRules {

    /** Only marketplace accounts are managed here; admin accounts are managed by the Super Admin screens. */
    public static final Set<Role> MANAGED_ROLES = EnumSet.of(Role.CUSTOMER, Role.PROVIDER);

    /** Status filter values accepted by the list and the export. */
    public static final Set<UserStatus> FILTER_STATUSES =
            EnumSet.of(UserStatus.ACTIVE, UserStatus.SUSPENDED, UserStatus.PENDING_VERIFICATION);

    public static final int DEFAULT_PAGE_SIZE = 8;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_USER_ID_LENGTH = 64;

    public static final int EXPORT_MAX_ROWS = 5000;
    /** Page size used while collecting export rows (the user directory caps pages at 100). */
    public static final int EXPORT_PAGE_SIZE = 100;

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;

    public static final int REGISTRATION_WEEKS = 8;
    public static final int REGISTRATION_MONTHS = 6;
    public static final int BOOKING_MONTHS = 6;
    public static final int RECENT_DISPUTES = 5;
    /** The account page's listings section: page size (default) and the largest page accepted. */
    public static final int LISTINGS_PAGE_SIZE = 5;
    public static final int MAX_LISTINGS_PAGE_SIZE = 20;
    public static final int QUEUED_BOOKINGS = 3;
    public static final int ADMIN_ACTIONS = 10;

    private AdminUsersRules() {
    }
}
