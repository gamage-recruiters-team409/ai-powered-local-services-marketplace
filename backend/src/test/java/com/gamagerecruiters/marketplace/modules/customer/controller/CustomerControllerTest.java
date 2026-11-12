package com.gamagerecruiters.marketplace.modules.customer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitExceededException;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerService;

@WebMvcTest(CustomerController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

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
    @DisplayName("GET /api/v1/customers/me returns 401 when unauthenticated")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me returns 403 when authenticated as PROVIDER")
    void shouldReturn403WhenProviderRole() throws Exception {
        mockAuth("provider-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(get("/api/v1/customers/me")
                        .header("Authorization", "Bearer provider-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me returns 403 when authenticated as ADMIN")
    void shouldReturn403WhenAdminRole() throws Exception {
        mockAuth("admin-token", "admin-1", Role.ADMIN);

        mockMvc.perform(get("/api/v1/customers/me")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me returns profile without passwordHash or tokens for CUSTOMER")
    void shouldReturnCustomerProfile() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerProfileResponse profile = new CustomerProfileResponse(
                "cust-1", "Kamal Perera", "kamal@marketplace.local", "+94771234567",
                Role.CUSTOMER, UserStatus.ACTIVE, true
        );
        when(customerService.getProfile("cust-1")).thenReturn(profile);

        mockMvc.perform(get("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cust-1"))
                .andExpect(jsonPath("$.fullName").value("Kamal Perera"))
                .andExpect(jsonPath("$.email").value("kamal@marketplace.local"))
                .andExpect(jsonPath("$.phone").value("+94771234567"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.failedLoginCount").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me updates profile with valid payload")
    void shouldUpdateProfileWhenValid() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        UpdateCustomerProfileRequest req = new UpdateCustomerProfileRequest("Kamal Silva", "0779876543");
        CustomerProfileResponse updated = new CustomerProfileResponse(
                "cust-1", "Kamal Silva", "kamal@marketplace.local", "+94779876543",
                Role.CUSTOMER, UserStatus.ACTIVE, true
        );
        when(customerService.updateProfile(eq("cust-1"), any())).thenReturn(updated);

        mockMvc.perform(put("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kamal Silva"))
                .andExpect(jsonPath("$.phone").value("+94779876543"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me returns 400 when full name is blank")
    void shouldReturn400WhenNameIsBlank() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        UpdateCustomerProfileRequest req = new UpdateCustomerProfileRequest("", "0779876543");

        mockMvc.perform(put("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/password returns 204 on success")
    void shouldChangePasswordSuccessfully() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", "NewPass123!");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(customerService).changePassword(eq("cust-1"), any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/password returns 400 on weak password")
    void shouldReturn400OnWeakPassword() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", "weak");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/addresses returns 201 Created on valid address")
    void shouldCreateAddressSuccessfully() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home", "123 Galle Rd", "Apt 2", "Colombo 03", "Colombo", "00300", true
        );
        CustomerAddressResponse res = new CustomerAddressResponse(
                "addr-1", "cust-1", "Home", "123 Galle Rd", "Apt 2", "Colombo 03", "Colombo", "00300",
                true, Instant.now(), Instant.now()
        );
        when(customerService.createAddress(eq("cust-1"), any())).thenReturn(res);

        mockMvc.perform(post("/api/v1/customers/me/addresses")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("addr-1"))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/addresses/{id} returns 204 on success")
    void shouldDeleteAddressSuccessfully() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(delete("/api/v1/customers/me/addresses/addr-1")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isNoContent());

        verify(customerService).deleteAddress("cust-1", "addr-1");
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/addresses/{id} returns 404 when address not found / unowned")
    void shouldReturn404WhenDeletingUnownedAddress() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        doThrow(new ResourceNotFoundException("ADDRESS_NOT_FOUND", "Address not found"))
                .when(customerService).deleteAddress("cust-1", "addr-99");

        mockMvc.perform(delete("/api/v1/customers/me/addresses/addr-99")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/addresses/{id}/default returns 200 on success")
    void shouldSetDefaultAddressSuccessfully() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerAddressResponse res = new CustomerAddressResponse(
                "addr-2", "cust-1", "Work", "456 Kandy Rd", null, "Kelaniya", "Gampaha", null,
                true, Instant.now(), Instant.now()
        );
        when(customerService.setDefaultAddress("cust-1", "addr-2")).thenReturn(res);

        mockMvc.perform(put("/api/v1/customers/me/addresses/addr-2/default")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("addr-2"))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 204 on success")
    void shouldDeactivateAccountSuccessfully() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        DeactivateAccountRequest req = new DeactivateAccountRequest("ValidPass123!");

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(customerService).deactivateAccount(eq("cust-1"), any(DeactivateAccountRequest.class));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 403 for PROVIDER role")
    void shouldReturn403WhenProviderDeactivates() throws Exception {
        mockAuth("prov-token", "prov-1", Role.PROVIDER);

        DeactivateAccountRequest req = new DeactivateAccountRequest("ValidPass123!");

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 400 on blank password")
    void shouldReturn400OnBlankPasswordDeactivate() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        DeactivateAccountRequest req = new DeactivateAccountRequest("");

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 409 when open bookings exist")
    void shouldReturn409WhenOpenBookingsExist() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        DeactivateAccountRequest req = new DeactivateAccountRequest("ValidPass123!");

        doThrow(new com.gamagerecruiters.marketplace.common.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "OPEN_BOOKINGS_EXIST",
                "You cannot deactivate your account while you have active bookings"
        )).when(customerService).deactivateAccount(eq("cust-1"), any());

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OPEN_BOOKINGS_EXIST"))
                .andExpect(jsonPath("$.message").value("You cannot deactivate your account while you have active bookings"));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 503 when deactivation check is unavailable (fail-closed)")
    void shouldReturn503WhenDeactivationUnavailable() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        DeactivateAccountRequest req = new DeactivateAccountRequest("ValidPass123!");

        doThrow(new com.gamagerecruiters.marketplace.common.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "DEACTIVATION_UNAVAILABLE",
                "Account deactivation is temporarily unavailable"
        )).when(customerService).deactivateAccount(eq("cust-1"), any());

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DEACTIVATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Account deactivation is temporarily unavailable"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/password returns 429 RATE_LIMITED when sensitive limit exceeded")
    void changePasswordReturns429WhenSensitiveRateLimited() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        org.mockito.Mockito.doThrow(new RateLimitExceededException(45))
                .when(rateLimitService).checkSensitive("cust-1");

        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", "NewPass123!");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));

        verify(customerService, never()).changePassword(any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/deactivate returns 429 RATE_LIMITED when sensitive limit exceeded")
    void deactivateReturns429WhenSensitiveRateLimited() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        org.mockito.Mockito.doThrow(new RateLimitExceededException(55))
                .when(rateLimitService).checkSensitive("cust-1");

        DeactivateAccountRequest req = new DeactivateAccountRequest("ValidPass123!");

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "55"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));

        verify(customerService, never()).deactivateAccount(any(), any());
    }
}
