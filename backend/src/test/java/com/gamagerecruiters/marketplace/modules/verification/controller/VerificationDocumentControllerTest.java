package com.gamagerecruiters.marketplace.modules.verification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

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
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentType;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDocumentResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationDocumentService;

@WebMvcTest(VerificationDocumentController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class VerificationDocumentControllerTest {

    private static final String BASE =
            "/api/v1/providers/me/documents";

    private static final String PROVIDER_TOKEN =
            "valid-provider-token";

    private static final String CUSTOMER_TOKEN =
            "valid-customer-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private VerificationDocumentService verificationDocumentService;

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

    @Test
    @DisplayName("Unauthenticated document request returns 401")
    void unauthenticatedReturns401() throws Exception {

        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error")
                                .value("UNAUTHORIZED")
                );
    }

    @Test
    @DisplayName("CUSTOMER cannot read provider verification documents")
    void customerCannotReadDocuments() throws Exception {

        mockUser(
                CUSTOMER_TOKEN,
                "customer-1",
                Role.CUSTOMER
        );

        mockMvc.perform(
                        get(BASE)
                                .header(
                                        "Authorization",
                                        "Bearer " + CUSTOMER_TOKEN
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.error")
                                .value("ACCESS_DENIED")
                );

        verify(
                verificationDocumentService,
                never()
        ).getMyDocuments();
    }

    @Test
    @DisplayName("PROVIDER can list own verification documents")
    void providerCanListDocuments() throws Exception {

        mockUser(
                PROVIDER_TOKEN,
                "provider-1",
                Role.PROVIDER
        );

        VerificationDocumentResponse response =
                new VerificationDocumentResponse(
                        "document-1",
                        VerificationDocumentType.ID,
                        "nic.pdf",
                        "pdf",
                        1200L,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2031, 1, 1),
                        VerificationDocumentStatus.SUBMITTED,
                        Instant.parse(
                                "2026-11-13T10:00:00Z"
                        )
                );

        when(
                verificationDocumentService
                        .getMyDocuments()
        ).thenReturn(List.of(response));

        mockMvc.perform(
                        get(BASE)
                                .header(
                                        "Authorization",
                                        "Bearer " + PROVIDER_TOKEN
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].id")
                                .value("document-1")
                )
                .andExpect(
                        jsonPath("$[0].documentType")
                                .value("ID")
                )
                .andExpect(
                        jsonPath("$[0].status")
                                .value("SUBMITTED")
                )
                .andExpect(
                        jsonPath("$[0].originalFileName")
                                .value("nic.pdf")
                );
    }

    @Test
    @DisplayName("PROVIDER can upload verification document")
    void providerCanUploadDocument() throws Exception {

        mockUser(
                PROVIDER_TOKEN,
                "provider-1",
                Role.PROVIDER
        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "nic.pdf",
                        "application/pdf",
                        "test-file-content".getBytes()
                );

        VerificationDocumentResponse response =
                new VerificationDocumentResponse(
                        "document-1",
                        VerificationDocumentType.ID,
                        "nic.pdf",
                        "pdf",
                        1200L,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2031, 1, 1),
                        VerificationDocumentStatus.SUBMITTED,
                        Instant.parse(
                                "2026-11-13T10:00:00Z"
                        )
                );

        when(
                verificationDocumentService
                        .uploadMyDocument(
                                eq(
                                        VerificationDocumentType.ID
                                ),
                                eq(
                                        LocalDate.of(
                                                2026,
                                                1,
                                                1
                                        )
                                ),
                                eq(
                                        LocalDate.of(
                                                2031,
                                                1,
                                                1
                                        )
                                ),
                                any(MultipartFile.class)
                        )
        ).thenReturn(response);

        mockMvc.perform(
                        multipart(BASE)
                                .file(file)
                                .param(
                                        "documentType",
                                        "ID"
                                )
                                .param(
                                        "issueDate",
                                        "2026-01-01"
                                )
                                .param(
                                        "expiryDate",
                                        "2031-01-01"
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + PROVIDER_TOKEN
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value("document-1")
                )
                .andExpect(
                        jsonPath("$.documentType")
                                .value("ID")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("SUBMITTED")
                );
    }

    @Test
    @DisplayName("CUSTOMER cannot upload provider verification document")
    void customerCannotUploadDocument() throws Exception {

        mockUser(
                CUSTOMER_TOKEN,
                "customer-1",
                Role.CUSTOMER
        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "nic.pdf",
                        "application/pdf",
                        "test".getBytes()
                );

        mockMvc.perform(
                        multipart(BASE)
                                .file(file)
                                .param(
                                        "documentType",
                                        "ID"
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + CUSTOMER_TOKEN
                                )
                )
                .andExpect(status().isForbidden());

        verify(
                verificationDocumentService,
                never()
        ).uploadMyDocument(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Invalid issue date format returns 400")
    void invalidIssueDateReturns400() throws Exception {

        mockUser(
                PROVIDER_TOKEN,
                "provider-1",
                Role.PROVIDER
        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "nic.pdf",
                        "application/pdf",
                        "test".getBytes()
                );

        mockMvc.perform(
                        multipart(BASE)
                                .file(file)
                                .param(
                                        "documentType",
                                        "ID"
                                )
                                .param(
                                        "issueDate",
                                        "01-01-2026"
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + PROVIDER_TOKEN
                                )
                )
                .andExpect(status().isBadRequest());

        verify(
                verificationDocumentService,
                never()
        ).uploadMyDocument(
                any(),
                any(),
                any(),
                any()
        );
    }
}