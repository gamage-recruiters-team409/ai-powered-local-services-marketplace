package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;

/** Access rules and input checks. Real queries are covered by BookingStatsMongoIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class BookingStatsFacadeImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    @Mock
    private MongoTemplate mongoTemplate;

    private BookingStatsFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new BookingStatsFacadeImpl(mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAs(Role role) {
        UserPrincipal principal = new UserPrincipal("u-1", "u-1@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("Customers and providers → 403, database not called")
    void nonAdminForbidden(Role role) {
        loginAs(role);
        assertThatThrownBy(() -> facade.findOverdueBookings(10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> facade.getUserStats("u-2")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> facade.countByStatus()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mongoTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN", "MODERATOR"})
    @DisplayName("Every admin role can read stats")
    void adminRolesAllowed(Role role) {
        loginAs(role);
        when(mongoTemplate.find(any(Query.class), eq(Booking.class))).thenReturn(List.of());
        assertThat(facade.findOverdueBookings(10)).isEmpty();
    }

    @Test
    @DisplayName("Not logged in inside an HTTP request → 401")
    void anonymousRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThatThrownBy(() -> facade.findOverdueBookings(10))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    @DisplayName("Background job (no user, no HTTP request) is allowed")
    void backgroundJob() {
        when(mongoTemplate.find(any(Query.class), eq(Booking.class))).thenReturn(List.of());
        assertThat(facade.findOverdueBookings(10)).isEmpty();
    }

    @Test
    @DisplayName("Bad inputs → 400 and the database is not called")
    void badInputs() {
        loginAs(Role.ADMIN);
        assertCode(() -> facade.findOverdueBookings(0), "INVALID_LIMIT");
        assertCode(() -> facade.findOverdueBookings(201), "INVALID_LIMIT");
        assertCode(() -> facade.findRepeatCancellations(0, 30), "INVALID_MIN_COUNT");
        assertCode(() -> facade.findRepeatCancellations(3, 0), "INVALID_DAYS");
        assertCode(() -> facade.findRepeatCancellations(3, 366), "INVALID_DAYS");
        assertCode(() -> facade.getUserStats(" "), "USER_ID_REQUIRED");
        assertCode(() -> facade.countCreatedPerDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19)),
                "INVALID_DATE_RANGE");
        assertCode(() -> facade.countCreatedPerDay(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)),
                "INVALID_DATE_RANGE");
        assertCode(() -> facade.countCreatedPerDay(null, LocalDate.of(2026, 1, 2)), "INVALID_DATE_RANGE");
        verifyNoInteractions(mongoTemplate);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
    }
}
