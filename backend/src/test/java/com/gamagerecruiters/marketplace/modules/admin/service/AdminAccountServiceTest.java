package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final String ACTOR_ID = "super-1";
    private static final String TARGET_ID = "admin-7";
    private static final String AUDIT_ID = "audit-1";
    private static final String REASON = "Repeated policy violations reported";
    private static final String PASSWORD = "TempPass#2026xyz";

    @Mock
    private UserInternalService userInternalService;
    @Mock
    private AdminAuditLogService auditLogService;

    private AdminAccountService service;

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(userInternalService, auditLogService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private void auditInsertReturnsId() {
        when(auditLogService.recordPending(any(AdminAuditLog.class))).thenAnswer(invocation -> {
            AdminAuditLog log = invocation.getArgument(0);
            ReflectionTestUtils.setField(log, "id", AUDIT_ID);
            return log;
        });
    }

    private AdminAuditLog capturedPending() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogService).recordPending(captor.capture());
        return captor.getValue();
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // =====================================================================================
    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        private AdminStatusChangeResponse suspend(String targetId) {
            return service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, targetId, UserStatus.SUSPENDED, REASON);
        }

        private void assertNoAuditAndNoChange() {
            verify(auditLogService, never()).recordPending(any());
            verify(userInternalService, never()).changeStatus(anyString(), any());
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
        @DisplayName("Suspends an admin account: PENDING record first, then auth changeStatus, then SUCCESS")
        void suspendsAdminAccount(Role targetRole) {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, targetRole, UserStatus.ACTIVE));
            auditInsertReturnsId();
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED))
                    .thenReturn(user(TARGET_ID, targetRole, UserStatus.SUSPENDED));

            AdminStatusChangeResponse result = suspend(TARGET_ID);

            assertThat(result.userId()).isEqualTo(TARGET_ID);
            assertThat(result.role()).isEqualTo(targetRole);
            assertThat(result.previousStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(result.auditLogId()).isEqualTo(AUDIT_ID);

            AdminAuditLog record = capturedPending();
            assertThat(record.getOutcome()).isEqualTo(AuditOutcome.PENDING);
            assertThat(record.getAction()).isEqualTo(AdminAuditAction.ADMIN_STATUS_CHANGED);
            assertThat(record.getActorId()).isEqualTo(ACTOR_ID);
            assertThat(record.getActorRole()).isEqualTo(Role.SUPER_ADMIN);
            assertThat(record.getTargetUserId()).isEqualTo(TARGET_ID);
            assertThat(record.getTargetRole()).isEqualTo(targetRole);
            assertThat(record.getFromStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(record.getToStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(record.getReason()).isEqualTo(REASON);
            assertThat(record.getCreatedAt()).isEqualTo(NOW);

            verify(auditLogService).markSuccess(AUDIT_ID);
            verify(auditLogService, never()).markFailed(anyString(), anyString());
        }

        @Test
        @DisplayName("Reactivates a SUSPENDED admin; the reason is stored trimmed")
        void reactivatesAdmin() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.SUSPENDED));
            auditInsertReturnsId();
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.ACTIVE))
                    .thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.ACTIVE));

            AdminStatusChangeResponse result = service.changeStatus(
                    ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.ACTIVE, "   Appeal accepted by the board   ");

            assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(capturedPending().getReason()).isEqualTo("Appeal accepted by the board");
            verify(auditLogService).markSuccess(AUDIT_ID);
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = {"LOCKED", "DEACTIVATED", "PENDING_VERIFICATION"})
        @NullSource
        @DisplayName("Rejects statuses other than ACTIVE/SUSPENDED with 400 STATUS_NOT_ALLOWED; no audit, no change")
        void rejectsDisallowedStatus(UserStatus status) {
            Throwable thrown = catchThrowable(
                    () -> service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, status, REASON));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.STATUS_NOT_ALLOWED);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "too short", "         x         "})
        @DisplayName("Rejects a missing or short reason (under 10 characters after trimming) with 400 VALIDATION_FAILED")
        void rejectsShortReason(String reason) {
            Throwable thrown = catchThrowable(
                    () -> service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.SUSPENDED, reason));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.VALIDATION_FAILED);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        @Test
        @DisplayName("Rejects a reason over 500 characters with 400 VALIDATION_FAILED")
        void rejectsLongReason() {
            Throwable thrown = catchThrowable(() -> service.changeStatus(
                    ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.SUSPENDED, "x".repeat(501)));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.VALIDATION_FAILED);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
        @DisplayName("Rejects non-admin targets with 404 ADMIN_ACCOUNT_NOT_FOUND")
        void rejectsNonAdminTarget(Role role) {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, role, UserStatus.ACTIVE));

            assertApiError(catchThrowable(() -> suspend(TARGET_ID)), HttpStatus.NOT_FOUND, AdminErrors.ADMIN_ACCOUNT_NOT_FOUND);
            assertNoAuditAndNoChange();
        }

        @Test
        @DisplayName("Rejects setting the status the account already has with 400 STATUS_UNCHANGED")
        void rejectsUnchangedStatus() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.SUSPENDED));

            assertApiError(catchThrowable(() -> suspend(TARGET_ID)), HttpStatus.BAD_REQUEST, AdminErrors.STATUS_UNCHANGED);
            assertNoAuditAndNoChange();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        @DisplayName("Blank or over-long target id -> 404 ADMIN_ACCOUNT_NOT_FOUND without a database lookup")
        void rejectsImpossibleId(String id) {
            assertApiError(catchThrowable(() -> suspend(id)), HttpStatus.NOT_FOUND, AdminErrors.ADMIN_ACCOUNT_NOT_FOUND);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        @Test
        @DisplayName("Unknown user: auth's 404 USER_NOT_FOUND passes through unchanged, no audit")
        void unknownUserPassesThrough() {
            ResourceNotFoundException notFound = new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
            when(userInternalService.getById(TARGET_ID)).thenThrow(notFound);

            assertThat(catchThrowable(() -> suspend(TARGET_ID))).isSameAs(notFound);
            assertNoAuditAndNoChange();
        }

        @Test
        @DisplayName("Self change is no longer blocked here: it reaches auth, which rejects it (403 CANNOT_MODIFY_SELF)")
        void selfChangeIsDecidedByAuth() {
            when(userInternalService.getById(ACTOR_ID)).thenReturn(user(ACTOR_ID, Role.SUPER_ADMIN, UserStatus.ACTIVE));
            auditInsertReturnsId();
            ApiException selfChange = new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_SELF", "Self status modification is not allowed");
            when(userInternalService.changeStatus(ACTOR_ID, UserStatus.SUSPENDED)).thenThrow(selfChange);

            assertThat(catchThrowable(() -> suspend(ACTOR_ID))).isSameAs(selfChange);
            verify(auditLogService).markFailed(AUDIT_ID, "CANNOT_MODIFY_SELF");
            verify(auditLogService, never()).markSuccess(anyString());
        }

        static Stream<Arguments> authRejections() {
            return Stream.of(
                    Arguments.of(HttpStatus.FORBIDDEN, "ACTOR_ACCOUNT_INACTIVE"),
                    Arguments.of(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_SELF"),
                    Arguments.of(HttpStatus.FORBIDDEN, "ROLE_HIERARCHY_VIOLATION"),
                    Arguments.of(HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED"),
                    Arguments.of(HttpStatus.CONFLICT, "LAST_ACTIVE_SUPER_ADMIN"));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("authRejections")
        @DisplayName("Auth rejection: record ends FAILED with the auth code; the same exception is re-thrown unchanged")
        void authRejectionPassesThrough(HttpStatus status, String code) {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.SUPER_ADMIN, UserStatus.ACTIVE));
            auditInsertReturnsId();
            ApiException rejection = new ApiException(status, code, "rejected by auth");
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED)).thenThrow(rejection);

            Throwable thrown = catchThrowable(() -> suspend(TARGET_ID));

            assertThat(thrown).isSameAs(rejection);
            assertApiError(thrown, status, code);
            verify(auditLogService).markFailed(AUDIT_ID, code);
            verify(auditLogService, never()).markSuccess(anyString());
        }

        @Test
        @DisplayName("AccessDeniedException from auth (e.g. @PreAuthorize): FAILED with ACCESS_DENIED")
        void accessDeniedFromAuth() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
            auditInsertReturnsId();
            AccessDeniedException denied = new AccessDeniedException("denied");
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED)).thenThrow(denied);

            assertThat(catchThrowable(() -> suspend(TARGET_ID))).isSameAs(denied);
            verify(auditLogService).markFailed(AUDIT_ID, "ACCESS_DENIED");
        }

        @Test
        @DisplayName("Even a JVM Error from auth leaves the record FAILED (never PENDING) and is re-thrown")
        void errorFromAuthIsRecordedFailed() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
            auditInsertReturnsId();
            StackOverflowError error = new StackOverflowError();
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED)).thenThrow(error);

            assertThat(catchThrowable(() -> suspend(TARGET_ID))).isSameAs(error);
            verify(auditLogService).markFailed(AUDIT_ID, "UNEXPECTED_ERROR");
        }

        @Test
        @DisplayName("If the PENDING record cannot be written, the status is not changed")
        void auditInsertFailureBlocksChange() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
            when(auditLogService.recordPending(any())).thenThrow(new IllegalStateException("db down"));

            assertThat(catchThrowable(() -> suspend(TARGET_ID))).isInstanceOf(IllegalStateException.class);
            verify(userInternalService, never()).changeStatus(anyString(), any());
        }

        @Test
        @DisplayName("If SUCCESS cannot be recorded after the change, the change is still reported")
        void successMarkFailureDoesNotHideChange() {
            when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
            auditInsertReturnsId();
            when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED))
                    .thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.SUSPENDED));
            doThrow(new IllegalStateException("db down")).when(auditLogService).markSuccess(AUDIT_ID);

            assertThat(suspend(TARGET_ID).status()).isEqualTo(UserStatus.SUSPENDED);
        }

        @Test
        @DisplayName("failureCodeOf reads the code from the exception type, never from the message")
        void failureCodeMapping() {
            assertThat(AdminErrors.failureCodeOf(new ResourceConflictException("SOME_CODE", "msg"))).isEqualTo("SOME_CODE");
            assertThat(AdminErrors.failureCodeOf(new AccessDeniedException("Self status modification is not allowed")))
                    .isEqualTo("ACCESS_DENIED");
            assertThat(AdminErrors.failureCodeOf(new IllegalStateException("anything"))).isEqualTo("UNEXPECTED_ERROR");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("createAdminAccount")
    class CreateAdminAccount {

        private static final String NEW_ID = "new-admin-1";

        private ListAppender<ILoggingEvent> logs;
        private Logger rootLogger;
    private ch.qos.logback.classic.Level previousLevel;

        @BeforeEach
        void captureLogs() {
            rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            logs = new ListAppender<>();
            logs.start();
            rootLogger.addAppender(logs);
            previousLevel = rootLogger.getLevel();
            rootLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
        }

        @AfterEach
        void stopLogs() {
            rootLogger.detachAppender(logs);
            rootLogger.setLevel(previousLevel);
        }

        private CreateAdminAccountRequest request(Role role) {
            return new CreateAdminAccountRequest("Nimal Perera", "nimal.ops@example.com", role, PASSWORD);
        }

        private User created(Role role) {
            User user = user(NEW_ID, role, UserStatus.ACTIVE);
            user.setName("Nimal Perera");
            user.setEmail("nimal.ops@example.com");
            user.setCreatedAt(NOW);
            user.setMustChangePassword(true);
            user.setPasswordHash("$2a$10$hash-that-must-never-leak");
            return user;
        }

        private void assertPasswordNeverLogged() {
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD);
                if (event.getThrowableProxy() != null) {
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(PASSWORD);
                }
            }
        }

        private static void assertAuditHasNoSecrets(AdminAuditLog record) {
            String all = String.join("|", String.valueOf(record.getActorId()), String.valueOf(record.getTargetUserId()),
                    String.valueOf(record.getReason()), String.valueOf(record.getFailureCode()));
            assertThat(all).doesNotContain(PASSWORD).doesNotContain("nimal.ops@example.com").doesNotContain("$2a$");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
        @DisplayName("Creates each allowed role: PENDING, auth createAdminAccount, SUCCESS with the new id; no password anywhere")
        void createsAllowedRole(Role role) {
            auditInsertReturnsId();
            when(userInternalService.createAdminAccount("Nimal Perera", "nimal.ops@example.com", PASSWORD, role))
                    .thenReturn(created(role));

            AdminAccountResponse response = service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request(role));

            assertThat(response.id()).isEqualTo(NEW_ID);
            assertThat(response.role()).isEqualTo(role);
            assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(response.mustChangePassword()).isTrue();
            assertThat(response.toString()).doesNotContain(PASSWORD).doesNotContain("$2a$");

            AdminAuditLog record = capturedPending();
            assertThat(record.getAction()).isEqualTo(AdminAuditAction.ADMIN_ACCOUNT_CREATED);
            assertThat(record.getActorId()).isEqualTo(ACTOR_ID);
            assertThat(record.getTargetRole()).isEqualTo(role);
            assertThat(record.getTargetUserId()).isNull();
            assertThat(record.getOutcome()).isEqualTo(AuditOutcome.PENDING);
            assertAuditHasNoSecrets(record);

            verify(auditLogService).markCreated(AUDIT_ID, NEW_ID);
            verify(auditLogService, never()).markFailed(anyString(), anyString());
            assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("Admin account created"));
            assertPasswordNeverLogged();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "CUSTOMER", "PROVIDER"})
        @NullSource
        @DisplayName("Rejects SUPER_ADMIN, CUSTOMER, PROVIDER (and no role) with 400 ROLE_NOT_ALLOWED; no audit, no auth call")
        void rejectsRole(Role role) {
            Throwable thrown = catchThrowable(() -> service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request(role)));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.ROLE_NOT_ALLOWED);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"short1A", "alllowercase99", "ALLUPPERCASE99", "NoDigitsHere", "Aa1"
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
        @DisplayName("Password policy (auth's regex, max 64 chars): 400 VALIDATION_FAILED; message and logs never contain it")
        void rejectsWeakOrLongPassword(String password) {
            CreateAdminAccountRequest request =
                    new CreateAdminAccountRequest("Nimal Perera", "nimal.ops@example.com", Role.ADMIN, password);

            Throwable thrown = catchThrowable(() -> service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.VALIDATION_FAILED);
            assertThat(thrown.getMessage()).doesNotContain(password);
            verifyNoInteractions(userInternalService, auditLogService);
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(password);
            }
        }

        @Test
        @DisplayName("Rejects a password over BCrypt's 72-byte limit with 400 VALIDATION_FAILED; message has no password")
        void rejectsPasswordOverByteLimit() {
            String multiByte = "Aa1" + "é".repeat(40); // 43 characters, 83 bytes
            CreateAdminAccountRequest request =
                    new CreateAdminAccountRequest("Nimal Perera", "nimal.ops@example.com", Role.ADMIN, multiByte);

            Throwable thrown = catchThrowable(() -> service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request));

            assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.VALIDATION_FAILED);
            assertThat(thrown.getMessage()).doesNotContain(multiByte);
            verifyNoInteractions(userInternalService, auditLogService);
        }

        static Stream<Arguments> authErrors() {
            return Stream.of(
                    Arguments.of(new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists"),
                            "EMAIL_ALREADY_EXISTS"),
                    Arguments.of(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "invalid role"), "INVALID_ROLE"),
                    Arguments.of(new AccessDeniedException("Only an active SUPER_ADMIN may create administrative accounts"),
                            "ACCESS_DENIED"),
                    Arguments.of(new IllegalStateException("db down"), "UNEXPECTED_ERROR"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("authErrors")
        @DisplayName("Auth error: record ends FAILED with its code, same exception re-thrown, password never logged")
        void authErrorPassesThrough(RuntimeException error, String code) {
            auditInsertReturnsId();
            when(userInternalService.createAdminAccount(anyString(), anyString(), anyString(), any())).thenThrow(error);

            assertThat(catchThrowable(() -> service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request(Role.ADMIN))))
                    .isSameAs(error);
            verify(auditLogService).markFailed(AUDIT_ID, code);
            verify(auditLogService, never()).markCreated(anyString(), anyString());
            assertAuditHasNoSecrets(capturedPending());
            assertPasswordNeverLogged();
        }

        @Test
        @DisplayName("If the PENDING record cannot be written, the account is not created")
        void auditInsertFailureBlocksCreate() {
            when(auditLogService.recordPending(any())).thenThrow(new IllegalStateException("db down"));

            assertThat(catchThrowable(() -> service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request(Role.ADMIN))))
                    .isInstanceOf(IllegalStateException.class);
            verify(userInternalService, never()).createAdminAccount(anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("If SUCCESS cannot be recorded, the created account is still returned (and logged without the password)")
        void successMarkFailureDoesNotHideCreate() {
            auditInsertReturnsId();
            when(userInternalService.createAdminAccount(anyString(), anyString(), anyString(), any()))
                    .thenReturn(created(Role.MODERATOR));
            doThrow(new IllegalStateException("db down")).when(auditLogService).markCreated(AUDIT_ID, NEW_ID);

            assertThat(service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN, request(Role.MODERATOR)).id()).isEqualTo(NEW_ID);
            assertPasswordNeverLogged();
        }

        @Test
        @DisplayName("The request's toString never shows the password")
        void requestToStringMasksPassword() {
            assertThat(request(Role.ADMIN).toString()).doesNotContain(PASSWORD).contains("***");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("listAccounts")
    class ListAccounts {

        private final AdminUserSummary summary = new AdminUserSummary(
                "admin-1", "Kamal Silva", "kamal@example.com", "0771234567", Role.ADMIN, UserStatus.ACTIVE, NOW);

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<Set<Role>> rolesCaptor() {
            return ArgumentCaptor.forClass(Set.class);
        }

        private void searchReturnsOne() {
            when(userInternalService.searchUsers(anyString(), any(), any(), any(), any(Pageable.class)))
                    .thenAnswer(inv -> new PageImpl<>(List.of(summary), inv.getArgument(4, Pageable.class), 1));
        }

        @Test
        @DisplayName("Defaults: the five admin roles only, no status, no search, page 0, size 20, createdAt desc; actorId passed")
        void defaults() {
            searchReturnsOne();

            AdminPageResponse<AdminAccountResponse> result =
                    service.listAccounts(ACTOR_ID, null, null, null, 0, 20, AdminAccountService.DEFAULT_SORT);

            ArgumentCaptor<Set<Role>> roles = rolesCaptor();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(userInternalService).searchUsers(eq(ACTOR_ID), roles.capture(), eq(null), eq(null), pageable.capture());
            assertThat(roles.getValue()).isEqualTo(Roles.ADMIN_ROLES).doesNotContain(Role.CUSTOMER, Role.PROVIDER);
            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).email()).isEqualTo("kamal@example.com");
            assertThat(result.content().get(0).mustChangePassword()).isNull();
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Filters: role (any case), status and trimmed search text are passed to searchUsers")
        void filters() {
            searchReturnsOne();

            service.listAccounts(ACTOR_ID, "moderator", "suspended", "  kamal@ex  ", 2, 50, "fullName,asc");

            ArgumentCaptor<Set<Role>> roles = rolesCaptor();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(userInternalService).searchUsers(eq(ACTOR_ID), roles.capture(), eq(UserStatus.SUSPENDED),
                    eq("kamal@ex"), pageable.capture());
            assertThat(roles.getValue()).containsExactly(Role.MODERATOR);
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
            assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "fullName"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"CUSTOMER", "PROVIDER", "BANNED", "ROOT"})
        @DisplayName("role filter outside the admin roles -> 400 INVALID_FILTER, no search")
        void rejectsRoleFilter(String role) {
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, role, null, null, 0, 20, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_FILTER);
            verifyNoInteractions(userInternalService);
        }

        @Test
        @DisplayName("Unknown status or search text over 100 characters -> 400 INVALID_FILTER")
        void rejectsStatusAndLongSearch() {
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, "BANNED", null, 0, 20, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_FILTER);
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, "a".repeat(101), 0, 20, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_FILTER);
            verifyNoInteractions(userInternalService);
        }

        @Test
        @DisplayName("page < 0 or size outside 1..100 -> 400 INVALID_PARAMETER")
        void rejectsPaging() {
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, null, -1, 20, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_PARAMETER);
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, null, 0, 0, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_PARAMETER);
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, null, 0, 101, null)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_PARAMETER);
            verifyNoInteractions(userInternalService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"passwordHash", "name", "role", "createdAt,up", "email,asc,extra", ",desc", "$where"})
        @DisplayName("Sort outside createdAt/fullName/email (asc|desc) -> 400 INVALID_PARAMETER")
        void rejectsSort(String sort) {
            assertApiError(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, null, 0, 20, sort)),
                    HttpStatus.BAD_REQUEST, AdminErrors.INVALID_PARAMETER);
            verifyNoInteractions(userInternalService);
        }

        @Test
        @DisplayName("Accepted sort forms")
        void acceptedSorts() {
            assertThat(AdminAccountService.parseSort("email")).isEqualTo(Sort.by(Sort.Direction.ASC, "email"));
            assertThat(AdminAccountService.parseSort("createdAt,DESC")).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
            assertThat(AdminAccountService.parseSort(null)).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        @Test
        @DisplayName("Auth search error (e.g. 403 ROLE_NOT_PERMITTED) passes through unchanged")
        void authErrorPassesThrough() {
            ApiException notPermitted = new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED", "not permitted");
            when(userInternalService.searchUsers(anyString(), any(), any(), any(), any(Pageable.class))).thenThrow(notPermitted);

            assertThat(catchThrowable(() -> service.listAccounts(ACTOR_ID, null, null, null, 0, 20, null))).isSameAs(notPermitted);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("Counts per admin role and per status from countByRoleAndStatus; never counts customers or providers")
        void countsByRoleAndStatus() {
            Map<Role, Map<UserStatus, Long>> data = Map.of(
                    Role.SUPER_ADMIN, Map.of(UserStatus.ACTIVE, 1L),
                    Role.ADMIN, Map.of(UserStatus.ACTIVE, 3L, UserStatus.SUSPENDED, 1L),
                    Role.MODERATOR, Map.of(UserStatus.ACTIVE, 2L, UserStatus.LOCKED, 1L));
            when(userInternalService.countByRoleAndStatus(eq(ACTOR_ID), any(Role.class), any(UserStatus.class)))
                    .thenAnswer(inv -> data.getOrDefault(inv.getArgument(1, Role.class), Map.of())
                            .getOrDefault(inv.getArgument(2, UserStatus.class), 0L));

            AdminAccountSummaryResponse summary = service.summary(ACTOR_ID);

            assertThat(summary.total()).isEqualTo(8);
            assertThat(summary.byRole()).containsExactly(
                    Map.entry(Role.SUPER_ADMIN, 1L), Map.entry(Role.ADMIN, 4L), Map.entry(Role.SUPPORT_ADMIN, 0L),
                    Map.entry(Role.MODERATOR, 3L), Map.entry(Role.FINANCE_ADMIN, 0L));
            assertThat(summary.byStatus()).containsEntry(UserStatus.ACTIVE, 6L).containsEntry(UserStatus.SUSPENDED, 1L)
                    .containsEntry(UserStatus.LOCKED, 1L).containsEntry(UserStatus.DEACTIVATED, 0L)
                    .containsEntry(UserStatus.PENDING_VERIFICATION, 0L);

            verify(userInternalService, never()).countByRoleAndStatus(anyString(), eq(Role.CUSTOMER), any());
            verify(userInternalService, never()).countByRoleAndStatus(anyString(), eq(Role.PROVIDER), any());
            verify(userInternalService, never()).countByRoleAndStatus(anyString(), eq(null), any());
            verify(userInternalService, never()).countByRoleAndStatus(argThat(id -> !ACTOR_ID.equals(id)), any(), any());
        }

        @Test
        @DisplayName("Auth count error passes through unchanged")
        void authErrorPassesThrough() {
            ApiException inactive = new ApiException(HttpStatus.FORBIDDEN, "ACTOR_ACCOUNT_INACTIVE", "inactive");
            when(userInternalService.countByRoleAndStatus(anyString(), any(), any())).thenThrow(inactive);

            assertThat(catchThrowable(() -> service.summary(ACTOR_ID))).isSameAs(inactive);
        }
    }
}
