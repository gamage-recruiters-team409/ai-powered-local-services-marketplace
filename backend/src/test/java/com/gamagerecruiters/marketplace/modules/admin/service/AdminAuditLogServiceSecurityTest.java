package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.repository.AdminAuditLogRepository;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.mongodb.client.result.UpdateResult;

/**
 * TL recommendation (a): the audit-log search() is guarded on the service itself (method security proxy), so
 * a caller that bypasses the controller still needs SUPER_ADMIN. The write methods used by the workflows are
 * deliberately not guarded and must keep working for any caller.
 */
@SpringJUnitConfig(AdminAuditLogServiceSecurityTest.MethodSecurityConfig.class)
class AdminAuditLogServiceSecurityTest {

    @Configuration
    @EnableMethodSecurity
    @Import({AdminAccessPolicyService.class, AdminAuditLogService.class})
    static class MethodSecurityConfig {
    }

    @MockitoBean
    private AdminAuditLogRepository repository;
    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Autowired
    private AdminAuditLogService auditLogService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Role role) {
        UserPrincipal principal = new UserPrincipal("user-" + role, role + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("search() as SUPER_ADMIN runs the query")
    void superAdminCanSearch() {
        authenticateAs(Role.SUPER_ADMIN);

        assertThat(auditLogService.search(null, null, null, null, null, 0, 20).content()).isEmpty();
        verify(mongoTemplate).find(any(Query.class), eq(AdminAuditLog.class));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("search() called directly as any other role -> AccessDeniedException, no query")
    void otherRolesCannotSearch(Role role) {
        authenticateAs(role);

        assertThatThrownBy(() -> auditLogService.search(null, null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        verify(mongoTemplate, never()).find(any(Query.class), eq(AdminAuditLog.class));
    }

    @Test
    @DisplayName("search() with no authentication at all -> rejected (missing credentials), no query")
    void anonymousCannotSearch() {
        // Spring Security wraps the missing-credentials error raised while evaluating the expression.
        assertThatThrownBy(() -> auditLogService.search(null, null, null, null, null, 0, 20))
                .hasRootCauseInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verify(mongoTemplate, never()).find(any(Query.class), eq(AdminAuditLog.class));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "MODERATOR", "CUSTOMER"})
    @DisplayName("Write methods (recordPending, markSuccess, markCreated, markFailed, recordReconciliation) are not role-guarded")
    void writeMethodsAreNotGuarded(Role role) {
        authenticateAs(role);
        AdminAuditLog pending = AdminAuditLog.pending("corr-1", "super-1", Role.SUPER_ADMIN,
                AdminAuditAction.ADMIN_STATUS_CHANGED, "admin-7", Role.ADMIN, UserStatus.ACTIVE, UserStatus.SUSPENDED,
                "Repeated violations", Instant.parse("2026-11-16T08:00:00Z"));
        ReflectionTestUtils.setField(pending, "id", "audit-1");
        when(repository.insert(any(AdminAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(auditLogService.recordPending(pending)).isSameAs(pending);
        auditLogService.markSuccess("audit-1");
        auditLogService.markCreated("audit-2", "new-1");
        auditLogService.markFailed("audit-3", "X");
        assertThat(auditLogService.recordReconciliation(pending, "admin-7", "AUDIT_COMPLETION_FAILED").getAction())
                .isEqualTo(AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("Write methods also work with no authentication at all (e.g. called from a workflow)")
    void writeMethodsWorkWithoutAuthentication() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        auditLogService.markSuccess("audit-1");
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class));
    }
}
