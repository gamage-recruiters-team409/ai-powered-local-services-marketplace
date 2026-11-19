package com.gamagerecruiters.marketplace.modules.announcement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementPageResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementSummaryResponse;
import com.gamagerecruiters.marketplace.modules.announcement.service.AnnouncementService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/** Role access for every announcement endpoint, through the real SecurityConfig and admin access policy. */
@WebMvcTest(AdminAnnouncementController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AnnouncementExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminAnnouncementControllerTest {

    private static final String BASE = "/api/v1/admin/announcements";
    private static final String ID = "ann-1";
    private static final String BODY = "{\"title\":\"Maintenance\",\"message\":\"We are **offline** on Sunday\","
            + "\"audience\":\"ALL_USERS\",\"sendPush\":true,\"pinToHeader\":true,\"intent\":\"PUBLISH\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;
    @MockitoBean
    private AnnouncementService announcementService;

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

    private static AnnouncementResponse response() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        return new AnnouncementResponse(ID, "Maintenance", "We are **offline** on Sunday", AnnouncementAudience.ALL_USERS,
                AnnouncementStatus.PUBLISHED, null, now, true, true, now.plusSeconds(3600), true,
                DeliveryState.NOT_APPLIED, "user-ADMIN", "user-ADMIN", now, now);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void stubReads() {
        when(announcementService.list(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AnnouncementPageResponse<>(List.of(response()), 0, 5, 1, 1));
        when(announcementService.summary()).thenReturn(
                new AnnouncementSummaryResponse(1, 0, 1, new AnnouncementSummaryResponse.Tabs(1, 1, 0, 0)));
        when(announcementService.get(ID)).thenReturn(response());
    }

    // ---------- 401 ----------

    @Test
    @DisplayName("every endpoint: 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get(BASE + "/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post(BASE), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(BASE + "/" + ID), BODY)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(announcementService);
    }

    // ---------- reads ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("reads (list, summary, detail): ADMIN and SUPER_ADMIN (read-only) -> 200")
    void readsAllowed(Role role) throws Exception {
        stubReads();
        String auth = bearer(role);

        mockMvc.perform(get(BASE).header("Authorization", auth)
                        .param("status", "PUBLISHED").param("audience", "ALL_USERS").param("q", "maint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ID))
                .andExpect(jsonPath("$.version").doesNotExist());
        verify(announcementService).list("PUBLISHED", "ALL_USERS", "maint", 0, 5);
        mockMvc.perform(get(BASE + "/summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.tabs.published").value(1));
        mockMvc.perform(get(BASE + "/" + ID).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryState").value("NOT_APPLIED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("reads: every other role -> 403")
    void readsForbidden(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(get(BASE).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(get(BASE + "/summary").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ID).header("Authorization", auth)).andExpect(status().isForbidden());
        verifyNoInteractions(announcementService);
    }

    // ---------- writes ----------

    @Test
    @DisplayName("writes: ADMIN -> 201 / 200 / 204, always as the logged-in admin")
    void adminCanWrite() throws Exception {
        when(announcementService.create(eq("user-ADMIN"), any())).thenReturn(response());
        when(announcementService.update(eq("user-ADMIN"), eq(ID), any())).thenReturn(response());
        String auth = bearer(Role.ADMIN);

        mockMvc.perform(json(post(BASE), BODY).header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("user-ADMIN"));
        mockMvc.perform(json(put(BASE + "/" + ID), BODY).header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/" + ID).header("Authorization", auth)).andExpect(status().isNoContent());
        verify(announcementService).delete("user-ADMIN", ID);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER",
            "PROVIDER"})
    @DisplayName("writes: SUPER_ADMIN (read-only), other admins, customers and providers -> 403")
    void othersCannotWrite(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(json(post(BASE), BODY).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(json(put(BASE + "/" + ID), BODY).header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/" + ID).header("Authorization", auth)).andExpect(status().isForbidden());
        verifyNoInteractions(announcementService);
    }

    // ---------- validation and error codes ----------

    @Test
    @DisplayName("validation: raw HTML, too-long title, missing intent -> 400 VALIDATION_FAILED with field errors")
    void validation() throws Exception {
        String auth = bearer(Role.ADMIN);
        String html = "{\"title\":\"Hi\",\"message\":\"<script>alert(1)</script>\",\"audience\":\"ALL_USERS\","
                + "\"intent\":\"DRAFT\"}";
        String longTitle = "{\"title\":\"" + "t".repeat(81) + "\",\"message\":\"m\",\"audience\":\"ALL_USERS\","
                + "\"intent\":\"DRAFT\"}";
        String noIntent = "{\"title\":\"Hi\",\"message\":\"m\",\"audience\":\"ALL_USERS\"}";

        mockMvc.perform(json(post(BASE), html).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("message"));
        mockMvc.perform(json(post(BASE), longTitle).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
        mockMvc.perform(json(put(BASE + "/" + ID), noIntent).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("intent"));
        mockMvc.perform(json(post(BASE), BODY.replace("ALL_USERS", "EVERYONE")).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        verifyNoInteractions(announcementService);
    }

    @Test
    @DisplayName("service conflicts keep their code: 409 ANNOUNCEMENT_NOT_DELETABLE / FIELDS_LOCKED")
    void conflictCodes() throws Exception {
        doThrow(new ResourceConflictException("ANNOUNCEMENT_NOT_DELETABLE", "published"))
                .when(announcementService).delete("user-ADMIN", ID);
        when(announcementService.update(eq("user-ADMIN"), eq(ID), any()))
                .thenThrow(new ResourceConflictException("ANNOUNCEMENT_FIELDS_LOCKED", "locked"));
        String auth = bearer(Role.ADMIN);

        mockMvc.perform(delete(BASE + "/" + ID).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANNOUNCEMENT_NOT_DELETABLE"));
        mockMvc.perform(json(put(BASE + "/" + ID), BODY).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ANNOUNCEMENT_FIELDS_LOCKED"));
    }

    @Test
    @DisplayName("bad page parameter -> 400 INVALID_PARAMETER (not 500)")
    void badPage() throws Exception {
        mockMvc.perform(get(BASE).param("page", "abc").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }
}
