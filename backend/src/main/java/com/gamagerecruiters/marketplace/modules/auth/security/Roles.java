package com.gamagerecruiters.marketplace.modules.auth.security;

import java.util.Set;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String PROVIDER = "PROVIDER";
    public static final String ADMIN = "ADMIN";
    public static final String SUPPORT_ADMIN = "SUPPORT_ADMIN";
    public static final String FINANCE_ADMIN = "FINANCE_ADMIN";
    public static final String MODERATOR = "MODERATOR";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    public static final String ROLE_PROVIDER = "ROLE_PROVIDER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_SUPPORT_ADMIN = "ROLE_SUPPORT_ADMIN";
    public static final String ROLE_FINANCE_ADMIN = "ROLE_FINANCE_ADMIN";
    public static final String ROLE_MODERATOR = "ROLE_MODERATOR";
    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    public static final Set<Role> ADMIN_ROLES = Set.of(
            Role.ADMIN,
            Role.SUPPORT_ADMIN,
            Role.FINANCE_ADMIN,
            Role.MODERATOR,
            Role.SUPER_ADMIN
    );

    public static final Set<String> ADMIN_ROLE_NAMES = Set.of(
            ADMIN,
            SUPPORT_ADMIN,
            FINANCE_ADMIN,
            MODERATOR,
            SUPER_ADMIN
    );

    private Roles() {
    }
}
