
package com.gamagerecruiters.marketplace.modules.verification.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAdminService;

@WebMvcTest(VerificationAdminController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class VerificationAdminControllerTest {

    private static final String BASE =
            "/api/v1/admin/provider-verifications";

    private static final String PROVIDER_ID = "provider-1";

    private static final String ADMIN_TOKEN =
            "valid-admin-token";

    private static final String SUPER_ADMIN_TOKEN =
            "valid-super-admin-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private VerificationAdminService verificationAdminService;

    private void mockUser(
            String token,
            String userId,
            Role role
    ) {
        when(tokenService.validateToken(token))
                .thenReturn(true);

        when(tokenService.getUserIdFromToken(token))
                .thenReturn(userId);

        when(tokenService.getRoleFromToken(token))
                .thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);

        when(userInternalService.getById(userId))
                .thenReturn(user);
    }

    private String authorization(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("Unauthenticated admin request returns 401")
    void unauthenticatedRequestReturns401() throws Exception {

        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN can access verification queue, details and documents")
    void adminCanReadVerificationInformation() throws Exception {

        mockUser(
                ADMIN_TOKEN,
                "admin-1",
                Role.ADMIN
        );

        when(verificationAdminService.getPendingVerifications())
                .thenReturn(List.of());

        // Read verification queue
        mockMvc.perform(
                        get(BASE)
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Read provider verification details
        mockMvc.perform(
                        get(BASE + "/" + PROVIDER_ID)
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Access private verification document
        mockMvc.perform(
                        get(
                                BASE + "/" + PROVIDER_ID
                                        + "/documents/document-1/access"
                        )
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        verify(verificationAdminService)
                .getPendingVerifications();

        verify(verificationAdminService)
                .getVerificationDetails(PROVIDER_ID);

        verify(verificationAdminService)
                .getVerificationDocumentAccess(
                        PROVIDER_ID,
                        "document-1"
                );
    }

    @Test
    @DisplayName("SUPER_ADMIN can view verification queue, details and documents")
    void superAdminCanReadVerificationInformation() throws Exception {

        mockUser(
                SUPER_ADMIN_TOKEN,
                "super-admin-1",
                Role.SUPER_ADMIN
        );

        when(verificationAdminService.getPendingVerifications())
                .thenReturn(List.of());

        // Read verification queue
        mockMvc.perform(
                        get(BASE)
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Read provider verification details
        mockMvc.perform(
                        get(BASE + "/" + PROVIDER_ID)
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Access private verification document
        mockMvc.perform(
                        get(
                                BASE + "/" + PROVIDER_ID
                                        + "/documents/document-1/access"
                        )
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN can perform verification workflow decisions")
    void adminCanPerformVerificationDecisions() throws Exception {

        mockUser(
                ADMIN_TOKEN,
                "admin-1",
                Role.ADMIN
        );

        // Start review
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/start-review"
                        )
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Approve verification
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/approve"
                        )
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isOk());

        // Reject verification
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/reject"
                        )
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "reason": "Invalid verification document"
                                        }
                                        """
                                )
                )
                .andExpect(status().isOk());

        // Request resubmission
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/request-resubmission"
                        )
                                .header(
                                        "Authorization",
                                        authorization(ADMIN_TOKEN)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "reason": "Updated documents are required"
                                        }
                                        """
                                )
                )
                .andExpect(status().isOk());

        verify(verificationAdminService)
                .startReview(PROVIDER_ID);

        verify(verificationAdminService)
                .approveVerification(PROVIDER_ID);

        verify(verificationAdminService)
                .rejectVerification(
                        PROVIDER_ID,
                        "Invalid verification document"
                );

        verify(verificationAdminService)
                .requestResubmission(
                        PROVIDER_ID,
                        "Updated documents are required"
                );
    }

    @Test
    @DisplayName("SUPER_ADMIN cannot modify verification workflow")
    void superAdminCannotPerformVerificationDecisions() throws Exception {

        mockUser(
                SUPER_ADMIN_TOKEN,
                "super-admin-1",
                Role.SUPER_ADMIN
        );

        // SUPER_ADMIN cannot start review
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/start-review"
                        )
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isForbidden());

        // SUPER_ADMIN cannot approve
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/approve"
                        )
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                )
                .andExpect(status().isForbidden());

        // SUPER_ADMIN cannot reject
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/reject"
                        )
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "reason": "Test rejection"
                                        }
                                        """
                                )
                )
                .andExpect(status().isForbidden());

        // SUPER_ADMIN cannot request resubmission
        mockMvc.perform(
                        post(
                                BASE + "/" + PROVIDER_ID
                                        + "/request-resubmission"
                        )
                                .header(
                                        "Authorization",
                                        authorization(SUPER_ADMIN_TOKEN)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                            "reason": "Test resubmission"
                                        }
                                        """
                                )
                )
                .andExpect(status().isForbidden());

        // Ensure no modification service methods were executed
        verify(verificationAdminService, never())
                .startReview(PROVIDER_ID);

        verify(verificationAdminService, never())
                .approveVerification(PROVIDER_ID);

        verify(verificationAdminService, never())
                .rejectVerification(
                        PROVIDER_ID,
                        "Test rejection"
                );

        verify(verificationAdminService, never())
                .requestResubmission(
                        PROVIDER_ID,
                        "Test resubmission"
                );
    }

    @Test
    @DisplayName("Restricted admin and user roles cannot access verification administration")
    void restrictedRolesAreDenied() throws Exception {

        List<Role> restrictedRoles = List.of(
                Role.SUPPORT_ADMIN,
                Role.MODERATOR,
                Role.FINANCE_ADMIN,
                Role.CUSTOMER,
                Role.PROVIDER
        );

        for (Role role : restrictedRoles) {

            String token =
                    "restricted-" + role.name().toLowerCase();

            mockUser(
                    token,
                    "user-" + role.name().toLowerCase(),
                    role
            );

            // Restricted roles cannot view verification queue
            mockMvc.perform(
                            get(BASE)
                                    .header(
                                            "Authorization",
                                            authorization(token)
                                    )
                    )
                    .andExpect(status().isForbidden());

            // Restricted roles cannot modify verification workflow
            mockMvc.perform(
                            post(
                                    BASE + "/" + PROVIDER_ID
                                            + "/start-review"
                            )
                                    .header(
                                            "Authorization",
                                            authorization(token)
                                    )
                    )
                    .andExpect(status().isForbidden());
        }

        // Confirm restricted requests never reach the service
        verify(verificationAdminService, never())
                .getPendingVerifications();

        verify(verificationAdminService, never())
                .startReview(PROVIDER_ID);
    }
}
