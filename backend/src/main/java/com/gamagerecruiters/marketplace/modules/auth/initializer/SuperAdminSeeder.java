package com.gamagerecruiters.marketplace.modules.auth.initializer;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

@Component
@Profile("!test")
public class SuperAdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String superAdminEmail;
    private final String superAdminPassword;

    public SuperAdminSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.super-admin.email:}") String superAdminEmail,
            @Value("${app.auth.super-admin.password:}") String superAdminPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.superAdminEmail = superAdminEmail;
        this.superAdminPassword = superAdminPassword;
    }

    @Override
    public void run(String... args) {
        if (superAdminEmail == null || superAdminEmail.isBlank() ||
            superAdminPassword == null || superAdminPassword.isBlank()) {
            return;
        }

        // Must create a SUPER_ADMIN only if no user with role SUPER_ADMIN exists
        if (userRepository.existsByRole(Role.SUPER_ADMIN)) {
            return;
        }

        String normalizedEmail = superAdminEmail.toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.info("User with configured seed email already exists without SUPER_ADMIN role. Skipping seed.");
            return;
        }

        User superAdmin = new User();
        superAdmin.setName("Super Admin");
        superAdmin.setEmail(normalizedEmail);
        // Phone is left absent (null) for admin accounts
        superAdmin.setPhone(null);
        superAdmin.setPasswordHash(passwordEncoder.encode(superAdminPassword));
        superAdmin.setRole(Role.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        superAdmin.setEmailVerified(true);
        superAdmin.setConsentStatus(true);
        superAdmin.setConsentAt(Instant.now());
        superAdmin.setConsentPolicyVersion("v1.0-seed");
        superAdmin.setFailedLoginCount(0);
        superAdmin.setCreatedAt(Instant.now());

        userRepository.save(superAdmin);
        log.info("Super Admin account seeded successfully for email: {}", normalizedEmail);
    }
}
