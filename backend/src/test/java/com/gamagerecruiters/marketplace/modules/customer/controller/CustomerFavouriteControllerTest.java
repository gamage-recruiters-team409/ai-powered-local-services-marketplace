package com.gamagerecruiters.marketplace.modules.customer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouriteResponse;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerFavouriteService;

@WebMvcTest(CustomerFavouriteController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class CustomerFavouriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerFavouriteService customerFavouriteService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private void mockAuth(String token, String userId, Role role) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getEmailFromToken(token)).thenReturn(userId + "@marketplace.local");
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User activeUser = new User();
        activeUser.setId(userId);
        activeUser.setEmail(userId + "@marketplace.local");
        activeUser.setRole(role);
        activeUser.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(activeUser);
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/favorites returns 401 when unauthenticated")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me/favorites?type=PROVIDER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/favorites returns 403 when authenticated as PROVIDER")
    void shouldReturn403WhenProviderRole() throws Exception {
        mockAuth("prov-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(get("/api/v1/customers/me/favorites?type=PROVIDER")
                        .header("Authorization", "Bearer prov-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/favorites returns 403 when authenticated as ADMIN")
    void shouldReturn403WhenAdminRole() throws Exception {
        mockAuth("admin-token", "admin-1", Role.ADMIN);

        mockMvc.perform(get("/api/v1/customers/me/favorites?type=PROVIDER")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/favorites returns 400 when type is invalid")
    void shouldReturn400WhenInvalidType() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(get("/api/v1/customers/me/favorites?type=INVALID_TYPE")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FAVOURITE_TYPE"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/favorites returns 200 with list when authenticated as CUSTOMER")
    void shouldReturn200ForCustomerGetFavourites() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerFavouriteResponse item = new CustomerFavouriteResponse(
                "fav-1", FavouriteType.PROVIDER, "prov-100", "Sunil Shanta",
                null, null, "Colombo", null, null, null, null, null, null, false, Instant.now()
        );
        CustomerFavouritePageResponse pageResponse = new CustomerFavouritePageResponse(
                List.of(item), 0, 6, 1, 1
        );
        when(customerFavouriteService.getFavourites("cust-1", FavouriteType.PROVIDER, 0, 6))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/customers/me/favorites?type=PROVIDER&page=0&size=6")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].favouriteId").value("fav-1"))
                .andExpect(jsonPath("$.content[0].targetId").value("prov-100"))
                .andExpect(jsonPath("$.content[0].name").value("Sunil Shanta"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/favorites/{type}/{targetId} returns 200 when adding favourite")
    void shouldReturn200ForAddFavourite() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(put("/api/v1/customers/me/favorites/PROVIDER/prov-100")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk());

        verify(customerFavouriteService).addFavourite("cust-1", FavouriteType.PROVIDER, "prov-100");
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/favorites/{type}/{targetId} returns 204 when removing favourite")
    void shouldReturn204ForRemoveFavourite() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(delete("/api/v1/customers/me/favorites/PROVIDER/prov-100")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isNoContent());

        verify(customerFavouriteService).removeFavourite("cust-1", FavouriteType.PROVIDER, "prov-100");
    }
}
