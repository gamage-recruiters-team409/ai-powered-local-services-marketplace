package com.gamagerecruiters.marketplace.modules.dispute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
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
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAdminSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/** Roles, validation and error codes of the decision, appeal, appeal-decision and summary endpoints. */
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
class DisputeResolutionWebTest {

    private static final String DISPUTES = "/api/v1/disputes";
    private static final String ADMIN_DISPUTES = "/api/v1/admin/disputes";
    private static final String ID = "dispute-1";
    private static final String RESOLVE_BODY =
            "{\"action\":\"REFUND_PARTIAL\",\"customerRefundPercent\":40,\"applyProviderPenalty\":true,\"note\":\"ok\"}";
    private static final String DECISION_BODY = "{\"verdict\":\"UPHOLD\",\"note\":\"reviewed\"}";
    private static final String APPEAL_BODY = "{\"grounds\":\"The refund is too low\",\"evidence\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;
    @MockitoBean
    private DisputeService disputeService;
    @MockitoBean
    private DisputeResolutionService resolutionService;
    @MockitoBean
    private DisputeAdminQueryService adminQueryService;

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

    private static AdminDisputeDetailResponse adminDetail() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new AdminDisputeDetailResponse(ID, "booking-1", "cust-1", null, "prov-1", null, "cust-1", Role.CUSTOMER,
                DisputeCategory.NO_SHOW, "x", List.of(), DisputeStatus.RESOLVED, null, now, now, now, null,
                now.plusSeconds(3600), AppealStatus.OPEN, null, null, null, null, List.of());
    }

    private static DisputeDetailResponse partyDetail() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new DisputeDetailResponse(ID, "booking-1", "cust-1", "prov-1", "cust-1", Role.CUSTOMER,
                DisputeCategory.NO_SHOW, "x", List.of(), DisputeStatus.APPEALED, null, now, now, now, List.of());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder resolve(String body) {
        return json(post(ADMIN_DISPUTES + "/" + ID + "/resolve"), body);
    }

    private static MockHttpServletRequestBuilder decide(String body) {
        return json(post(ADMIN_DISPUTES + "/" + ID + "/appeal/decision"), body);
    }

    private static MockHttpServletRequestBuilder appeal(String body) {
        return json(post(DISPUTES + "/" + ID + "/appeal"), body);
    }

    // ---------- 401 ----------

    @Test
    @DisplayName("new endpoints return 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(resolve(RESOLVE_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(decide(DECISION_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(appeal(APPEAL_BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_DISPUTES + "/summary")).andExpect(status().isUnauthorized());
        verifyNoInteractions(resolutionService, adminQueryService);
    }

    // ---------- admin writes: ADMIN only ----------

    @Test
    @DisplayName("POST /admin/disputes/{id}/resolve and /appeal/decision: ADMIN (edit permission) -> 200 as itself")
    void adminCanWrite() throws Exception {
        when(resolutionService.resolve(eq("user-ADMIN"), eq(Role.ADMIN), eq(ID), any())).thenReturn(adminDetail());
        when(resolutionService.decideAppeal(eq("user-ADMIN"), eq(Role.ADMIN), eq(ID), any())).thenReturn(adminDetail());
        String auth = bearer(Role.ADMIN);

        mockMvc.perform(resolve(RESOLVE_BODY).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        mockMvc.perform(decide(DECISION_BODY).header("Authorization", auth)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER",
            "PROVIDER"})
    @DisplayName("admin writes: SUPER_ADMIN (read-only), other admins, customers and providers -> 403")
    void othersCannotWrite(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(resolve(RESOLVE_BODY).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(decide(DECISION_BODY).header("Authorization", auth)).andExpect(status().isForbidden());
        verifyNoInteractions(resolutionService);
    }

    // ---------- appeal: parties only ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("POST /disputes/{id}/appeal: customer and provider -> 200 as themselves")
    void partiesCanAppeal(Role role) throws Exception {
        when(resolutionService.appeal(eq("user-" + role.name()), eq(role), eq(ID), any())).thenReturn(partyDetail());

        mockMvc.perform(appeal(APPEAL_BODY).header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPEALED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("POST /disputes/{id}/appeal: admin roles -> 403")
    void adminsCannotAppeal(Role role) throws Exception {
        mockMvc.perform(appeal(APPEAL_BODY).header("Authorization", bearer(role))).andExpect(status().isForbidden());
        verifyNoInteractions(resolutionService);
    }

    // ---------- admin reads ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("GET /admin/disputes/summary and the filtered list: ADMIN and SUPER_ADMIN (read-only) -> 200")
    void summaryAndListAllowed(Role role) throws Exception {
        when(adminQueryService.summary()).thenReturn(new DisputeAdminSummaryResponse(1, 2, 3, 4, Instant.EPOCH, 5));
        when(adminQueryService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new DisputePageResponse<>(List.of(), 0, 20, 0, 0));
        String auth = bearer(role);

        mockMvc.perform(get(ADMIN_DISPUTES + "/summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appealed").value(3))
                .andExpect(jsonPath("$.resolvedLast30Days").value(4))
                .andExpect(jsonPath("$.manualReview").value(5));
        mockMvc.perform(get(ADMIN_DISPUTES).header("Authorization", auth)
                        .param("status", "UNDER_REVIEW").param("category", "NO_SHOW").param("q", "booking-1")
                        .param("from", "2026-11-01").param("to", "2026-11-17").param("sort", "createdAt,asc"))
                .andExpect(status().isOk());
        verify(adminQueryService).search("UNDER_REVIEW", "NO_SHOW", "booking-1", LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-17"), "createdAt,asc", 0, 20);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("GET /admin/disputes/summary: every other role -> 403")
    void summaryForbidden(Role role) throws Exception {
        mockMvc.perform(get(ADMIN_DISPUTES + "/summary").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminQueryService);
    }

    @Test
    @DisplayName("GET /admin/disputes: a malformed date -> 400 INVALID_PARAMETER")
    void badDate() throws Exception {
        mockMvc.perform(get(ADMIN_DISPUTES).param("from", "17-11-2026").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        verify(adminQueryService, org.mockito.Mockito.never())
                .search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------- validation ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"customerRefundPercent\":40,\"note\":\"ok\"}",
            "{\"action\":\"REFUND_FULL\",\"note\":\"   \"}",
            "{\"action\":\"REFUND_FULL\"}"})
    @DisplayName("resolve: missing action or blank/missing note -> 400 VALIDATION_FAILED")
    void resolveValidation(String body) throws Exception {
        mockMvc.perform(resolve(body).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("resolve: note longer than 1000 characters -> 400; unknown action -> 400 MALFORMED_REQUEST")
    void resolveLimits() throws Exception {
        String auth = bearer(Role.ADMIN);
        mockMvc.perform(resolve("{\"action\":\"REFUND_FULL\",\"note\":\"" + "a".repeat(1001) + "\"}")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        mockMvc.perform(resolve("{\"action\":\"KEEP_IT\",\"note\":\"x\"}").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("appeal decision: missing verdict -> 400; appeal: blank grounds or 11 evidence items -> 400")
    void appealValidation() throws Exception {
        mockMvc.perform(decide("{\"note\":\"x\"}").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        String customer = bearer(Role.CUSTOMER);
        mockMvc.perform(appeal("{\"grounds\":\"  \"}").header("Authorization", customer))
                .andExpect(status().isBadRequest());
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(11, "\"e.jpg\"")) + "]";
        mockMvc.perform(appeal("{\"grounds\":\"x\",\"evidence\":" + tooMany + "}").header("Authorization", customer))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(resolutionService);
    }

    // ---------- service error codes over HTTP ----------

    @Test
    @DisplayName("service errors keep their status and code: 403 APPEAL_REVIEWER_MUST_DIFFER, 409 APPEAL_WINDOW_CLOSED, "
            + "400 INVALID_REFUND_PERCENT")
    void errorCodesOverHttp() throws Exception {
        when(resolutionService.decideAppeal(any(), any(), eq(ID), any())).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "APPEAL_REVIEWER_MUST_DIFFER", "different admin required"));
        when(resolutionService.appeal(any(), any(), eq(ID), any()))
                .thenThrow(new ResourceConflictException("APPEAL_WINDOW_CLOSED", "closed"));
        when(resolutionService.resolve(any(), any(), eq(ID), any())).thenThrow(new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_REFUND_PERCENT", "1-99"));

        mockMvc.perform(decide(DECISION_BODY).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("APPEAL_REVIEWER_MUST_DIFFER"));
        mockMvc.perform(appeal(APPEAL_BODY).header("Authorization", bearer(Role.PROVIDER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPEAL_WINDOW_CLOSED"));
        mockMvc.perform(resolve(RESOLVE_BODY).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REFUND_PERCENT"));
    }

    @Test
    @DisplayName("GET /admin/disputes/summary is not taken as a dispute id")
    void summaryIsNotAnId() throws Exception {
        when(adminQueryService.summary()).thenReturn(new DisputeAdminSummaryResponse(0, 0, 0, 0, Instant.EPOCH, 0));

        mockMvc.perform(get(ADMIN_DISPUTES + "/summary").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
        verify(adminQueryService, org.mockito.Mockito.never()).getDetail(isNull());
        verify(adminQueryService, org.mockito.Mockito.never()).getDetail("summary");
    }
}
