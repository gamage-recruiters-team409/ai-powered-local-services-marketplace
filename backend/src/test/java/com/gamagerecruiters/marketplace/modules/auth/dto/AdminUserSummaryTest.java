package com.gamagerecruiters.marketplace.modules.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

class AdminUserSummaryTest {

    @Test
    @DisplayName("AdminUserSummary must never expose password, token or sensitive security fields")
    void summaryMustNeverExposeSensitiveFields() {
        List<String> componentNames = Arrays.stream(AdminUserSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactlyInAnyOrder(
                "id", "fullName", "email", "phone", "role", "status", "createdAt"
        );

        List<String> fieldNames = Arrays.stream(AdminUserSummary.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        for (String fieldName : fieldNames) {
            String lower = fieldName.toLowerCase();
            assertFalse(lower.contains("password"), "Field " + fieldName + " must not contain password data");
            assertFalse(lower.contains("token"), "Field " + fieldName + " must not contain token data");
            assertFalse(lower.contains("mustchange"), "Field " + fieldName + " must not expose mustChangePassword");
            assertFalse(lower.contains("failed"), "Field " + fieldName + " must not expose failed logins");
        }
    }

    @Test
    @DisplayName("fromUser correctly maps fields from User domain entity")
    void mapsCorrectlyFromUser() {
        Instant now = Instant.now();
        User user = new User();
        user.setId("u-123");
        user.setName("John Doe");
        user.setEmail("john@example.com");
        user.setPhone("+94771234567");
        user.setPasswordHash("hashed-password-never-leaked");
        user.setRole(Role.ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(now);

        AdminUserSummary summary = AdminUserSummary.fromUser(user);

        assertEquals("u-123", summary.id());
        assertEquals("John Doe", summary.fullName());
        assertEquals("john@example.com", summary.email());
        assertEquals("+94771234567", summary.phone());
        assertEquals(Role.ADMIN, summary.role());
        assertEquals(UserStatus.ACTIVE, summary.status());
        assertEquals(now, summary.createdAt());
    }
}
