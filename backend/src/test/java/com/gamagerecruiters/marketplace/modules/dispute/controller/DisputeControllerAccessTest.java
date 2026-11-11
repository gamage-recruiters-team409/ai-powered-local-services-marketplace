package com.gamagerecruiters.marketplace.modules.dispute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/** Role access for every dispute endpoint, through the real SecurityConfig and admin access policy. */
@WebMvcTest({DisputeController.class, AdminDisputeController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        DisputeExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class DisputeControllerAccessTest {

    private static final String DISPUTES = "/api/v1/disputes";
    private static final String ADMIN_DISPUTES = "/api/v1/admin/disputes";
    private static final String DISPUTE_ID = "dispute-1";
    private static final String RAISE_BODY =
            "{\"bookingId\":\"booking-1\",\"category\":\"NO_SHOW\",\"description\":\"Provider did not come\","
                    + "\"evidence\":[\"evidence/photo-1.jpg\"]}";
    private static final String RESPONSE_BODY = "{\"text\":\"I arrived on time\",\"evidence\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private DisputeService disputeService;

    /** Registers a valid token for a user with the given role and returns the Authorization header value. */
    private String bearer(Role role) {
        String token = "token-" + role.name();
        String userId = "user-" + role.name();
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
        return "Bearer " + token;
    }

    private static DisputeDetailResponse detail() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new DisputeDetailResponse(DISPUTE_ID, "booking-1", "cust-1", "prov-1", "cust-1", Role.CUSTOMER,
                DisputeCategory.NO_SHOW, "Provider did not come", List.of(), DisputeStatus.AWAITING_RESPONSE,
                null, now.plusSeconds(3600), now, now, List.of());
    }

    private static DisputePageResponse<DisputeSummaryResponse> emptyPage() {
        return new DisputePageResponse<>(List.of(), 0, 20, 0, 0);
    }

    private static MockHttpServletRequestBuilder raise() {
        return post(DISPUTES).contentType(MediaType.APPLICATION_JSON).content(RAISE_BODY);
    }

    private static MockHttpServletRequestBuilder respond() {
        return post(DISPUTES + "/" + DISPUTE_ID + "/response").contentType(MediaType.APPLICATION_JSON).content(RESPONSE_BODY);
    }

    // ---------- 401 without a token ----------

    @Test
    @DisplayName("Every dispute endpoint returns 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(raise()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get(DISPUTES + "/my")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(DISPUTES + "/" + DISPUTE_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(respond()).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_DISPUTES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_DISPUTES + "/" + DISPUTE_ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(disputeService);
    }

    // ---------- POST /disputes ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("POST /disputes: customers and providers can raise (201)")
    void raiseAllowed(Role role) throws Exception {
        when(disputeService.raiseDispute(eq("user-" + role.name()), eq(role), any())).thenReturn(detail());

        mockMvc.perform(raise().header("Authorization", bearer(role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(DISPUTE_ID))
                .andExpect(jsonPath("$.status").value("AWAITING_RESPONSE"))
                .andExpect(jsonPath("$.activeBookingKey").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("POST /disputes: admin roles cannot raise (403)")
    void raiseForbiddenForAdmins(Role role) throws Exception {
        mockMvc.perform(raise().header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        verifyNoInteractions(disputeService);
    }

    @Test
    @DisplayName("POST /disputes: invalid body -> 400 VALIDATION_FAILED with field errors")
    void raiseValidation() throws Exception {
        String invalid = "{\"bookingId\":\"bad id!\",\"category\":\"NO_SHOW\",\"description\":\"\"}";

        mockMvc.perform(post(DISPUTES).contentType(MediaType.APPLICATION_JSON).content(invalid)
                        .header("Authorization", bearer(Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verifyNoInteractions(disputeService);
    }

    @Test
    @DisplayName("POST /disputes: unknown category -> 400 MALFORMED_REQUEST")
    void raiseUnknownCategory() throws Exception {
        String invalid = "{\"bookingId\":\"booking-1\",\"category\":\"BANANA\",\"description\":\"x\"}";

        mockMvc.perform(post(DISPUTES).contentType(MediaType.APPLICATION_JSON).content(invalid)
                        .header("Authorization", bearer(Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    // ---------- GET /disputes/my ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("GET /disputes/my: customers and providers (200) get their own disputes")
    void myDisputesAllowed(Role role) throws Exception {
        when(disputeService.getMyDisputes("user-" + role.name(), 0, 20)).thenReturn(emptyPage());

        mockMvc.perform(get(DISPUTES + "/my").header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(20));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("GET /disputes/my: admin roles (403)")
    void myDisputesForbiddenForAdmins(Role role) throws Exception {
        mockMvc.perform(get(DISPUTES + "/my").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    @Test
    @DisplayName("GET /disputes/my: non-numeric page -> 400 INVALID_PARAMETER (not 500)")
    void myDisputesBadPage() throws Exception {
        mockMvc.perform(get(DISPUTES + "/my").param("page", "abc").header("Authorization", bearer(Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    // ---------- GET /disputes/{id} ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("GET /disputes/{id}: parties go through the party check")
    void getDisputeAsParty(Role role) throws Exception {
        when(disputeService.getDisputeForParty("user-" + role.name(), DISPUTE_ID)).thenReturn(detail());

        mockMvc.perform(get(DISPUTES + "/" + DISPUTE_ID).header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DISPUTE_ID));
        verify(disputeService, never()).getDisputeForAdmin(any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("GET /disputes/{id}: ADMIN and SUPER_ADMIN (read-only) can view any dispute")
    void getDisputeAsAuthorizedAdmin(Role role) throws Exception {
        when(disputeService.getDisputeForAdmin(DISPUTE_ID)).thenReturn(detail());

        mockMvc.perform(get(DISPUTES + "/" + DISPUTE_ID).header("Authorization", bearer(role)))
                .andExpect(status().isOk());
        verify(disputeService, never()).getDisputeForParty(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("GET /disputes/{id}: admins without the disputes area (403)")
    void getDisputeForbiddenForOtherAdmins(Role role) throws Exception {
        mockMvc.perform(get(DISPUTES + "/" + DISPUTE_ID).header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    // ---------- POST /disputes/{id}/response ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("POST /disputes/{id}/response: parties can call it (the service decides who is the other party)")
    void respondAllowed(Role role) throws Exception {
        when(disputeService.submitResponse(eq("user-" + role.name()), eq(role), eq(DISPUTE_ID), any()))
                .thenReturn(detail());

        mockMvc.perform(respond().header("Authorization", bearer(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("POST /disputes/{id}/response: admin roles (403)")
    void respondForbiddenForAdmins(Role role) throws Exception {
        mockMvc.perform(respond().header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }

    @Test
    @DisplayName("POST /disputes/{id}/response: blank text -> 400 VALIDATION_FAILED")
    void respondValidation() throws Exception {
        mockMvc.perform(post(DISPUTES + "/" + DISPUTE_ID + "/response").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}")
                        .header("Authorization", bearer(Role.PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // ---------- /admin/disputes ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("GET /admin/disputes and /admin/disputes/{id}: ADMIN and SUPER_ADMIN (read-only) (200)")
    void adminEndpointsAllowed(Role role) throws Exception {
        when(disputeService.getDisputesForAdmin(eq("UNDER_REVIEW"), anyInt(), anyInt())).thenReturn(emptyPage());
        when(disputeService.getDisputesForAdmin(isNull(), anyInt(), anyInt())).thenReturn(emptyPage());
        when(disputeService.getDisputeForAdmin(DISPUTE_ID)).thenReturn(detail());
        String auth = bearer(role);

        mockMvc.perform(get(ADMIN_DISPUTES).header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(get(ADMIN_DISPUTES).param("status", "UNDER_REVIEW").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get(ADMIN_DISPUTES + "/" + DISPUTE_ID).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DISPUTE_ID));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("GET /admin/disputes and /admin/disputes/{id}: every other role (403)")
    void adminEndpointsForbidden(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(get(ADMIN_DISPUTES).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(get(ADMIN_DISPUTES + "/" + DISPUTE_ID).header("Authorization", auth))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeService);
    }
}
