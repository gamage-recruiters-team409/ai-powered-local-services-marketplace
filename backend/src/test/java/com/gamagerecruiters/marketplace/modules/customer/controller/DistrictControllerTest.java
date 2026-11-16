package com.gamagerecruiters.marketplace.modules.customer.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.dto.ProvinceDto;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;

@WebMvcTest(DistrictController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class DistrictControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DistrictCatalog districtCatalog;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @Test
    @DisplayName("GET /api/v1/locations/districts without auth returns 401 UNAUTHORIZED")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/locations/districts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/locations/districts when authenticated returns 200 with provinces and districts")
    void shouldReturnDistrictsWhenAuthenticated() throws Exception {
        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-1");
        when(tokenService.getEmailFromToken("valid-token")).thenReturn("user@marketplace.local");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.CUSTOMER);

        User activeUser = new User();
        activeUser.setId("u-1");
        activeUser.setEmail("user@marketplace.local");
        activeUser.setRole(Role.CUSTOMER);
        activeUser.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("u-1")).thenReturn(activeUser);

        when(districtCatalog.getProvinces()).thenReturn(List.of(
                new ProvinceDto("Western", List.of("Colombo", "Gampaha", "Kalutara"))
        ));

        mockMvc.perform(get("/api/v1/locations/districts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provinces[0].name").value("Western"))
                .andExpect(jsonPath("$.provinces[0].districts[0]").value("Colombo"));
    }
}
