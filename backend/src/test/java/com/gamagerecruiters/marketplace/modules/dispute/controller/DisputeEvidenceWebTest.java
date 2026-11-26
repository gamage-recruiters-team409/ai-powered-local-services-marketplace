package com.gamagerecruiters.marketplace.modules.dispute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceUrlResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceView;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeEvidenceService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/** Evidence endpoints and the My Disputes status filter over HTTP, through the real SecurityConfig. */
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
class DisputeEvidenceWebTest {

    private static final String DISPUTES = "/api/v1/disputes";
    private static final String ID = "dispute-1";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

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
    @MockitoBean
    private DisputeEvidenceService evidenceService;

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

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "door.png", "image/png", PNG);
    }

    // ---------- upload ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("POST /disputes/{id}/evidence: a party -> 201 with the file's metadata (no storage id, no URL)")
    void partyUploads(Role role) throws Exception {
        when(evidenceService.upload(eq("user-" + role.name()), eq(ID), any())).thenReturn(new EvidenceView("ev-1",
                "door.png", EvidenceType.PNG, "image/png", PNG.length, role, Instant.parse("2026-11-20T10:00:00Z")));

        mockMvc.perform(multipart(DISPUTES + "/" + ID + "/evidence").file(file()).header("Authorization", bearer(role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ev-1"))
                .andExpect(jsonPath("$.type").value("PNG"))
                .andExpect(jsonPath("$.publicId").doesNotExist())
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "MODERATOR", "SUPPORT_ADMIN", "FINANCE_ADMIN"})
    @DisplayName("POST /disputes/{id}/evidence: admins cannot upload party evidence -> 403")
    void adminsCannotUpload(Role role) throws Exception {
        mockMvc.perform(multipart(DISPUTES + "/" + ID + "/evidence").file(file()).header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(evidenceService);
    }

    @Test
    @DisplayName("POST /disputes/{id}/evidence without a token -> 401")
    void uploadNeedsLogin() throws Exception {
        mockMvc.perform(multipart(DISPUTES + "/" + ID + "/evidence").file(file()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(evidenceService);
    }

    // ---------- signed URL ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "ADMIN", "SUPER_ADMIN"})
    @DisplayName("GET .../evidence/{evidenceId}/url: parties and ADMIN / SUPER_ADMIN (read) -> 200, never cached")
    void signedUrlAllowed(Role role) throws Exception {
        boolean admin = role == Role.ADMIN || role == Role.SUPER_ADMIN;
        when(evidenceService.signedUrl("user-" + role.name(), admin, ID, "ev-1"))
                .thenReturn(new EvidenceUrlResponse("https://files.example.test/signed", Instant.now().plusSeconds(300)));

        mockMvc.perform(get(DISPUTES + "/" + ID + "/evidence/ev-1/url").header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.url").exists());
        verify(evidenceService).signedUrl("user-" + role.name(), admin, ID, "ev-1");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MODERATOR", "SUPPORT_ADMIN", "FINANCE_ADMIN"})
    @DisplayName("GET .../evidence/{evidenceId}/url: other admin roles -> 403")
    void signedUrlForbidden(Role role) throws Exception {
        mockMvc.perform(get(DISPUTES + "/" + ID + "/evidence/ev-1/url").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(evidenceService);
    }

    // ---------- My Disputes status filter ----------

    @Test
    @DisplayName("GET /disputes/my?status=A&status=B,C -> parsed to a set; no status -> every status")
    void myDisputesStatusFilter() throws Exception {
        when(disputeService.getMyDisputes(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new DisputePageResponse<>(List.of(), 0, 20, 0, 0));
        String auth = bearer(Role.CUSTOMER);

        mockMvc.perform(get(DISPUTES + "/my").param("status", "awaiting_response")
                        .param("status", "UNDER_REVIEW,APPEALED").header("Authorization", auth))
                .andExpect(status().isOk());
        verify(disputeService).getMyDisputes("user-CUSTOMER",
                EnumSet.of(DisputeStatus.AWAITING_RESPONSE, DisputeStatus.UNDER_REVIEW, DisputeStatus.APPEALED), 0, 20);

        mockMvc.perform(get(DISPUTES + "/my").header("Authorization", auth)).andExpect(status().isOk());
        verify(disputeService).getMyDisputes("user-CUSTOMER", Set.of(), 0, 20);
    }

    @Test
    @DisplayName("GET /disputes/my with an unknown status -> 400 INVALID_STATUS_FILTER")
    void myDisputesUnknownStatus() throws Exception {
        mockMvc.perform(get(DISPUTES + "/my").param("status", "DELETED").header("Authorization", bearer(Role.PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_FILTER"));
    }
}
