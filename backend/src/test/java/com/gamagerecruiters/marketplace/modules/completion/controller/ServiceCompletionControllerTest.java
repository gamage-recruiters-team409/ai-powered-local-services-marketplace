package com.gamagerecruiters.marketplace.modules.completion.controller;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;

@WebMvcTest(ServiceCompletionController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ServiceCompletionControllerTest {

    private static final String BASE = "/api/v1/bookings/booking-1/completion";
    private static final String PROVIDER_TOKEN = "valid-provider-token";
    private static final String CUSTOMER_TOKEN = "valid-customer-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ServiceCompletionService completionService;

    private void mockUser(String token, String userId, Role role) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
    }

    private static ServiceCompletionResponse response(Instant markedAt, Instant confirmedAt,
                                                      CompletionConfirmationType type) {
        return new ServiceCompletionResponse("comp-1", "booking-1", Instant.parse("2026-11-11T08:00:00Z"),
                markedAt == null ? null : "Done", List.of(), markedAt, confirmedAt, type, null, null);
    }

    @Test
    @DisplayName("Unauthenticated start returns 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post(BASE + "/start"))
                .andExpect(status().isUnauthorized());
        verify(completionService, never()).startService(anyString(), anyString());
    }

    @Test
    @DisplayName("PROVIDER starts the service (201)")
    void providerStartsService() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(completionService.startService("booking-1", "prov-1")).thenReturn(response(null, null, null));

        mockMvc.perform(post(BASE + "/start").header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value("booking-1"))
                .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    @DisplayName("CUSTOMER cannot start the service (403)")
    void customerCannotStart() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        mockMvc.perform(post(BASE + "/start").header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden());
        verify(completionService, never()).startService(anyString(), anyString());
    }

    @Test
    @DisplayName("PROVIDER marks completed with note and photos (200)")
    void providerMarksCompleted() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(completionService.markCompleted(eq("booking-1"), eq("prov-1"), any()))
                .thenReturn(response(Instant.parse("2026-11-11T10:00:00Z"), null, null));

        mockMvc.perform(post(BASE + "/mark-completed")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionNote\":\"Done\",\"photoUrls\":[\"https://img/1.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedCompletedAt").exists());
    }

    @Test
    @DisplayName("More than 5 photos returns 400 VALIDATION_FAILED")
    void rejectsTooManyPhotos() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(post(BASE + "/mark-completed")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoUrls\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(completionService, never()).markCompleted(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("CUSTOMER confirms completion (200)")
    void customerConfirms() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(completionService.confirmCompletion("booking-1", "cust-1")).thenReturn(response(
                Instant.parse("2026-11-11T10:00:00Z"), Instant.parse("2026-11-11T11:00:00Z"),
                CompletionConfirmationType.CUSTOMER));

        mockMvc.perform(post(BASE + "/confirm").header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationType").value("CUSTOMER"));
    }

    @Test
    @DisplayName("PROVIDER cannot confirm completion (403)")
    void providerCannotConfirm() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(post(BASE + "/confirm").header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isForbidden());
        verify(completionService, never()).confirmCompletion(anyString(), anyString());
    }

    @Test
    @DisplayName("Business errors from the service are returned with their error code (409)")
    void serviceConflictIsReturned() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(completionService.startService("booking-1", "prov-1")).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "SERVICE_ALREADY_STARTED", "Already started"));

        mockMvc.perform(post(BASE + "/start").header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SERVICE_ALREADY_STARTED"));
    }

    @Test
    @DisplayName("CUSTOMER raises an issue (200); reason under 10 characters returns 400")
    void customerRaisesIssue() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(completionService.raiseIssue(eq("booking-1"), eq("cust-1"), any()))
                .thenReturn(response(Instant.parse("2026-11-11T10:00:00Z"), null, null));

        mockMvc.perform(post(BASE + "/raise-issue")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Balcony was not cleaned.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/raise-issue")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PROVIDER cannot raise an issue (403)")
    void providerCannotRaiseIssue() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(post(BASE + "/raise-issue")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Balcony was not cleaned.\"}"))
                .andExpect(status().isForbidden());
        verify(completionService, never()).raiseIssue(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PROVIDER reports a no-show without a body (200)")
    void providerReportsNoShow() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(completionService.reportNoShow(eq("booking-1"), eq("prov-1"), any())).thenReturn(
                new NoShowReportResponse("booking-1", "NO_SHOW_BY_CUSTOMER", "DISPUTED", Instant.parse("2026-11-11T10:00:00Z")));

        mockMvc.perform(post(BASE + "/no-show").header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("NO_SHOW_BY_CUSTOMER"))
                .andExpect(jsonPath("$.bookingStatus").value("DISPUTED"));
    }

    @Test
    @DisplayName("CUSTOMER reads the completion (200)")
    void customerReadsCompletion() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(completionService.getCompletion("booking-1", "cust-1")).thenReturn(response(null, null, null));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("comp-1"));
    }
}
