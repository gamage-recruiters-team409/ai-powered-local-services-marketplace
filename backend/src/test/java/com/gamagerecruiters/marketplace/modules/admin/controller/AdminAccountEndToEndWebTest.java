package com.gamagerecruiters.marketplace.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccountService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

/**
 * HTTP -> real AdminAccountService -> mocked auth service. Proves the 400 codes for filters and sort, that
 * the actor id reaching the auth module is the principal's, and that the password never appears in the
 * response, the audit record or the logs.
 */
@WebMvcTest(AdminAccountController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminExceptionHandler.class,
        AdminAccessPolicyService.class,
        AdminAccountService.class
})
@ActiveProfiles("test")
class AdminAccountEndToEndWebTest {

    private static final String ACCOUNTS_URL = "/api/v1/admin/accounts";
    private static final String SUPER_ID = "super-1";
    private static final String TOKEN = "token-super";
    private static final String PASSWORD = "SecretTemp#4711Qx";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private AdminAuditLogService auditLogService;

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void setUp() {
        when(tokenService.validateToken(TOKEN)).thenReturn(true);
        when(tokenService.getUserIdFromToken(TOKEN)).thenReturn(SUPER_ID);
        when(tokenService.getRoleFromToken(TOKEN)).thenReturn(Role.SUPER_ADMIN);
        User actor = new User();
        actor.setId(SUPER_ID);
        actor.setEmail("root@test.local");
        actor.setRole(Role.SUPER_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(SUPER_ID)).thenReturn(actor);

        when(auditLogService.recordPending(any(AdminAuditLog.class))).thenAnswer(inv -> {
            AdminAuditLog log = inv.getArgument(0);
            ReflectionTestUtils.setField(log, "id", "audit-9");
            return log;
        });

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        // Capture everything, including Spring's DEBUG/TRACE request-body logging.
        previousLevel = rootLogger.getLevel();
        rootLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(previousLevel);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + TOKEN);
    }

    private static String createBody(String password) {
        return "{\"fullName\":\"Nimal Perera\",\"email\":\"nimal.ops@example.com\",\"role\":\"FINANCE_ADMIN\","
                + "\"temporaryPassword\":\"" + password + "\"}";
    }

    private void assertNotLogged(String secret) {
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(secret);
            if (event.getThrowableProxy() != null) {
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(secret);
            }
        }
    }

    private AdminAuditLog pendingRecord() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogService).recordPending(captor.capture());
        return captor.getValue();
    }

    // ---------- create ----------

    @Test
    @DisplayName("Create: 201, password passed only to auth; not in response, audit record or logs")
    void createNeverLeaksPassword() throws Exception {
        User created = new User();
        created.setId("new-9");
        created.setName("Nimal Perera");
        created.setEmail("nimal.ops@example.com");
        created.setRole(Role.FINANCE_ADMIN);
        created.setStatus(UserStatus.ACTIVE);
        created.setCreatedAt(Instant.parse("2026-11-12T08:00:00Z"));
        created.setMustChangePassword(true);
        created.setPasswordHash("$2a$10$never-returned");
        when(userInternalService.createAdminAccount("Nimal Perera", "nimal.ops@example.com", PASSWORD, Role.FINANCE_ADMIN))
                .thenReturn(created);

        String response = mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-9"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(PASSWORD).doesNotContain("$2a$").doesNotContain("passwordHash");
        AdminAuditLog record = pendingRecord();
        assertThat(record.getActorId()).isEqualTo(SUPER_ID);
        assertThat(String.valueOf(record.getReason()) + record.getTargetUserId() + record.getFailureCode())
                .doesNotContain(PASSWORD).doesNotContain("nimal.ops@example.com");
        verify(auditLogService).markCreated("audit-9", "new-9");
        assertThat(logs.list).as("log capture is active").isNotEmpty();
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("Admin account created"));
        assertNotLogged(PASSWORD);
    }

    @Test
    @DisplayName("Create: auth's 409 EMAIL_ALREADY_EXISTS passes through, record FAILED, password not logged")
    void createDuplicateEmail() throws Exception {
        when(userInternalService.createAdminAccount(anyString(), anyString(), anyString(), any()))
                .thenThrow(new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists"));

        String response = mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(PASSWORD).doesNotContain("Exception").doesNotContain("at com.");
        verify(auditLogService).markFailed("audit-9", "EMAIL_ALREADY_EXISTS");
        assertNotLogged(PASSWORD);
    }

    @Test
    @DisplayName("Create: SUPER_ADMIN role -> 400 ROLE_NOT_ALLOWED before auth; weak password -> 400, never logged")
    void createRejections() throws Exception {
        mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(PASSWORD).replace("FINANCE_ADMIN", "SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ROLE_NOT_ALLOWED"));

        // Weak, too long, and a valid password inside a body that fails for another reason (bad role, bad email):
        // with logging at TRACE, none of these passwords may appear in the response or any log line.
        String weak = "alllowercase99";
        String tooLong = "Aa1" + "q".repeat(62);
        for (String password : new String[] {weak, tooLong}) {
            String response = mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(password)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(password);
        }
        mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(PASSWORD).replace("FINANCE_ADMIN", "ROOT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mockMvc.perform(authed(post(ACCOUNTS_URL)).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(PASSWORD).replace("nimal.ops@example.com", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userInternalService, never()).createAdminAccount(anyString(), anyString(), anyString(), any());
        verify(auditLogService, never()).recordPending(any());
        assertThat(logs.list).as("log capture is active").isNotEmpty();
        assertNotLogged(PASSWORD);
        assertNotLogged(weak);
        assertNotLogged(tooLong);
    }

    // ---------- list ----------

    @Test
    @DisplayName("List: actorId sent to auth is the principal's, even when ?actorId= is supplied; admin roles only")
    void listUsesPrincipal() throws Exception {
        when(userInternalService.searchUsers(anyString(), any(), any(), any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(new AdminUserSummary("a-1", "Kamal", "k@example.com", null,
                        Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-11-01T00:00:00Z"))), inv.getArgument(4, Pageable.class), 1));

        mockMvc.perform(authed(get(ACCOUNTS_URL)).param("actorId", "evil-actor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("a-1"))
                .andExpect(jsonPath("$.content[0].mustChangePassword").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(userInternalService).searchUsers(eq(SUPER_ID), eq(AdminAccountService.ADMIN_ROLE_ORDER.stream()
                .collect(java.util.stream.Collectors.toSet())), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("List: bad role/status/q -> 400 INVALID_FILTER; bad sort/size -> 400 INVALID_PARAMETER; auth never called")
    void listRejections() throws Exception {
        String[][] cases = {
                {"role", "CUSTOMER", "INVALID_FILTER"},
                {"role", "PROVIDER", "INVALID_FILTER"},
                {"status", "BANNED", "INVALID_FILTER"},
                {"q", "a".repeat(101), "INVALID_FILTER"},
                {"sort", "passwordHash,asc", "INVALID_PARAMETER"},
                {"sort", "createdAt,sideways", "INVALID_PARAMETER"},
                {"size", "500", "INVALID_PARAMETER"},
                {"size", "0", "INVALID_PARAMETER"},
                {"page", "-1", "INVALID_PARAMETER"},
        };
        for (String[] c : cases) {
            mockMvc.perform(authed(get(ACCOUNTS_URL)).param(c[0], c[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(c[2]));
        }
        verify(userInternalService, never()).searchUsers(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("List: regex-looking search text is passed as plain text (auth escapes it)")
    void searchIsPlainText() throws Exception {
        when(userInternalService.searchUsers(anyString(), any(), any(), any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(4, Pageable.class), 0));

        mockMvc.perform(authed(get(ACCOUNTS_URL)).param("q", ".*(a+)+$")).andExpect(status().isOk());

        verify(userInternalService).searchUsers(eq(SUPER_ID), any(), eq(null), eq(".*(a+)+$"), any(Pageable.class));
    }

    // ---------- service-level guard ----------

    @Autowired
    private AdminAccountService adminAccountService;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("Calling AdminAccountService directly (e.g. from another module) as a non-SUPER_ADMIN -> AccessDeniedException")
    void serviceIsGuardedToo(Role role) {
        UserPrincipal principal = new UserPrincipal("user-" + role, role + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            assertThatThrownBy(() -> adminAccountService.summary("user-" + role)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> adminAccountService.listAccounts("user-" + role, null, null, null, 0, 20, null))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> adminAccountService.createAdminAccount("user-" + role, role,
                    new CreateAdminAccountRequest("N", "n@example.com", Role.ADMIN, PASSWORD)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> adminAccountService.changeStatus("user-" + role, role, "x", UserStatus.SUSPENDED,
                    "Repeated policy violations")).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
        verify(userInternalService, never()).countByRoleAndStatus(anyString(), any(), any());
        verify(auditLogService, never()).recordPending(any());
    }

    // ---------- summary ----------

    @Test
    @DisplayName("Summary: every count uses the principal's id and only admin roles")
    void summaryUsesPrincipal() throws Exception {
        when(userInternalService.countByRoleAndStatus(anyString(), any(), any())).thenReturn(1L);

        mockMvc.perform(authed(get(ACCOUNTS_URL + "/summary")).param("actorId", "evil-actor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5 * UserStatus.values().length))
                .andExpect(jsonPath("$.byRole.SUPER_ADMIN").value(UserStatus.values().length))
                .andExpect(jsonPath("$.byRole.CUSTOMER").doesNotExist());

        verify(userInternalService, never()).countByRoleAndStatus(eq("evil-actor"), any(), any());
        verify(userInternalService, never()).countByRoleAndStatus(anyString(), eq(Role.CUSTOMER), any());
    }
}
