package com.gamagerecruiters.marketplace.modules.search.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.search.service.PublicProviderProfileService;
import com.gamagerecruiters.marketplace.modules.search.service.SearchService;

@WebMvcTest({SearchController.class, PublicProviderProfileController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class PublicSearchSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;
    @MockitoBean
    private PublicProviderProfileService publicProviderProfileService;
    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;

    @Test
    @DisplayName("Public search and provider profile GET endpoints allow anonymous access")
    void publicGetEndpointsAllowAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/providers/user-1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/providers/me remains protected")
    void providerMeRemainsProtected() throws Exception {
        mockMvc.perform(get("/api/v1/providers/me"))
                .andExpect(status().isUnauthorized());
    }
}
