package com.gamagerecruiters.marketplace.modules.customer.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
    @DisplayName("PUT /api/v1/customers/me returns 400 when full name contains digits")
    void shouldReturn400WhenNameContainsDigits() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        UpdateCustomerProfileRequest req = new UpdateCustomerProfileRequest("kalana test 14444", "0779876543");

        mockMvc.perform(put("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fullName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"e\u0301", "\u0301\u0301", "A.", "a\u0301 "})
    @DisplayName("F1: PUT /api/v1/customers/me returns 400 when full name has fewer than 2 letters")
    void shouldRejectProfileUpdateWhenNameHasFewerThanTwoLetters(String invalidName) throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        UpdateCustomerProfileRequest req = new UpdateCustomerProfileRequest(invalidName, "0779876543");

        mockMvc.perform(put("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fullName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "කමල් පෙරේරා",
            "கமல் பெரேரா",
            "José",
            "Zoë",
            "Anne-Marie",
            "O'Brien",
            "Kalana   Silva"
    })
    @DisplayName("F1: PUT /api/v1/customers/me accepts valid diverse names and collapses repeated spaces")
    void shouldAcceptProfileUpdateWithDiverseValidNames(String validName) throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerProfileResponse updated = new CustomerProfileResponse(
                "cust-1", "Kalana Silva", "kamal@marketplace.local", "+94779876543",
                Role.CUSTOMER, UserStatus.ACTIVE, true
        );
        when(customerService.updateProfile(eq("cust-1"), any())).thenReturn(updated);

        UpdateCustomerProfileRequest req = new UpdateCustomerProfileRequest(validName, "0779876543");

        mockMvc.perform(put("/api/v1/customers/me")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateCustomerProfileRequest> captor = ArgumentCaptor.forClass(UpdateCustomerProfileRequest.class);
        verify(customerService).updateProfile(eq("cust-1"), captor.capture());
        if (validName.equals("Kalana   Silva")) {
            assertEquals("Kalana Silva", captor.getValue().fullName());
        }
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
    @DisplayName("F2: PUT /api/v1/customers/me/password accepts new password with exactly 72 UTF-8 bytes")
    void shouldAcceptNewPasswordWithExactly72Bytes() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String exact72Bytes = "Aa1!" + "€".repeat(22) + "ab"; // 72 bytes
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", exact72Bytes);

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(customerService).changePassword(eq("cust-1"), any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("F2: PUT /api/v1/customers/me/password rejects new password with 73 UTF-8 bytes")
    void shouldRejectNewPasswordWith73Bytes() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String pw73Bytes = "Aa1!" + "€".repeat(23); // 73 bytes, 27 chars
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", pw73Bytes);

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));
    }

    @Test
    @DisplayName("F3: PUT /api/v1/customers/me/password allows current password longer than 72 characters when valid")
    void shouldAllowOverLongCurrentPasswordWhenValid() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String longCurrentPassword = "Old" + "a".repeat(100) + "123!";
        ChangePasswordRequest req = new ChangePasswordRequest(longCurrentPassword, "NewValidPass123!");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(customerService).changePassword(eq("cust-1"), any(ChangePasswordRequest.class));
    }

    @Test
    @DisplayName("F3: PUT /api/v1/customers/me/password returns 400 (never 500) when over-long current password is wrong")
    void shouldReturn400WhenOverLongCurrentPasswordIsWrong() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String longCurrentPassword = "Wrong" + "a".repeat(100) + "123!";
        doThrow(new com.gamagerecruiters.marketplace.common.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Current password is incorrect"
        )).when(customerService).changePassword(eq("cust-1"), any(ChangePasswordRequest.class));

        ChangePasswordRequest req = new ChangePasswordRequest(longCurrentPassword, "NewValidPass123!");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    @DisplayName("F3: POST /api/v1/customers/me/deactivate allows password confirmation longer than 72 characters when valid")
    void shouldAllowOverLongPasswordOnDeactivationWhenValid() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String longPassword = "Valid" + "a".repeat(100) + "123!";
        DeactivateAccountRequest req = new DeactivateAccountRequest(longPassword);

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(customerService).deactivateAccount(eq("cust-1"), any(DeactivateAccountRequest.class));
    }

    @Test
    @DisplayName("F3: POST /api/v1/customers/me/deactivate returns 400 (never 500) when password confirmation is wrong")
    void shouldReturn400WhenDeactivationPasswordIsWrong() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        String longPassword = "Wrong" + "a".repeat(100) + "123!";
        doThrow(new com.gamagerecruiters.marketplace.common.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password confirmation is incorrect"
        )).when(customerService).deactivateAccount(eq("cust-1"), any(DeactivateAccountRequest.class));

        DeactivateAccountRequest req = new DeactivateAccountRequest(longPassword);

        mockMvc.perform(post("/api/v1/customers/me/deactivate")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PASSWORD"));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/addresses returns 400 when city is all digits")
    void shouldReturn400WhenAddressCityIsAllDigits() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home", "123 Galle Rd", "Apt 2", "12345", "Colombo", "00300", true
        );

        mockMvc.perform(post("/api/v1/customers/me/addresses")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("city"));
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
    @DisplayName("POST /api/v1/customers/me/addresses accepts address with padded spaces and trims them")
    void shouldCreateAddressSuccessfullyWithPaddedSpaces() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "  Home  ", "  123 Galle Rd  ", "  Apt 2  ", "  Colombo 03  ", "  Colombo  ", "  00300  ", true
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

    private void assertAddressFieldValidationError(CustomerAddressRequest request, String expectedField) throws Exception {
        mockMvc.perform(post("/api/v1/customers/me/addresses")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(expectedField));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0007", "\u007F", "\u0085", "\u009F"})
    @DisplayName("F6: POST /api/v1/customers/me/addresses rejects control characters in label, addressLine1, addressLine2, and city at start, middle, and end")
    void shouldRejectControlCharactersInAddressFields(String controlChar) throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        // Control char in label (start, middle, end)
        assertAddressFieldValidationError(new CustomerAddressRequest(
                controlChar + "Home", "No. 5, Galle Road", "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "label");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Ho" + controlChar + "me", "No. 5, Galle Road", "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "label");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home" + controlChar, "No. 5, Galle Road", "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "label");

        // Control char in addressLine1 (start, middle, end)
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", controlChar + "No. 5, Galle Road", "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "addressLine1");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle" + controlChar + " Road", "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "addressLine1");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road" + controlChar, "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "addressLine1");

        // Control char in addressLine2 (start, middle, end)
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", controlChar + "Apt 2", "Colombo 03", "Colombo", "00300", false
        ), "addressLine2");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", "Ap" + controlChar + "t 2", "Colombo 03", "Colombo", "00300", false
        ), "addressLine2");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", "Apt 2" + controlChar, "Colombo 03", "Colombo", "00300", false
        ), "addressLine2");

        // Control char in city (start, middle, end)
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", "Apt 2", controlChar + "Colombo 03", "Colombo", "00300", false
        ), "city");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", "Apt 2", "Colombo " + controlChar + "03", "Colombo", "00300", false
        ), "city");
        assertAddressFieldValidationError(new CustomerAddressRequest(
                "Home", "No. 5, Galle Road", "Apt 2", "Colombo 03" + controlChar, "Colombo", "00300", false
        ), "city");
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
    @DisplayName("PUT /api/v1/customers/me/password returns 400 when new password exceeds 72 characters")
    void shouldReturn400WhenPasswordExceeds72Chars() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        String longPassword = "A1a" + "b".repeat(70); // 73 chars
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", longPassword);

        mockMvc.perform(put("/api/v1/customers/me/password")
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
    @DisplayName("PUT /api/v1/customers/me/password sets Retry-After header equal to exception retryAfterSeconds in seconds")
    void changePasswordRetryAfterHeaderEqualsExceptionSeconds() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        long expectedRetryAfterSeconds = 890L;
        org.mockito.Mockito.doThrow(new RateLimitExceededException(expectedRetryAfterSeconds))
                .when(rateLimitService).checkSensitive("cust-1");

        ChangePasswordRequest req = new ChangePasswordRequest("OldPass123!", "NewPass123!");

        mockMvc.perform(put("/api/v1/customers/me/password")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", String.valueOf(expectedRetryAfterSeconds)));
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

    @Test
    @DisplayName("PUT /api/v1/customers/me/photo returns 401 when unauthenticated")
    void uploadPhoto_unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[] { 1, 2 });
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/customers/me/photo").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/photo returns 403 when authenticated as PROVIDER")
    void uploadPhoto_providerRole_returns403() throws Exception {
        mockAuth("prov-token", "prov-1", Role.PROVIDER);
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[] { 1, 2 });
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/customers/me/photo")
                        .file(file)
                        .header("Authorization", "Bearer prov-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/photo returns 200 with profileImageUrl and never exposes publicId")
    void uploadPhoto_success_returnsProfileWithUrl() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[] { 1, 2 });

        CustomerProfileResponse res = new CustomerProfileResponse(
                "cust-1", "Kamal Perera", "kamal@marketplace.local", "+94771234567",
                Role.CUSTOMER, UserStatus.ACTIVE, true, "https://res.cloudinary.com/demo/image/upload/photo.jpg"
        );
        when(customerService.uploadProfilePhoto(eq("cust-1"), any())).thenReturn(res);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/customers/me/photo")
                        .file(file)
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cust-1"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://res.cloudinary.com/demo/image/upload/photo.jpg"))
                .andExpect(jsonPath("$.profileImagePublicId").doesNotExist())
                .andExpect(jsonPath("$.publicId").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/photo returns 401 when unauthenticated")
    void deletePhoto_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/customers/me/photo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/photo returns 403 when authenticated as PROVIDER")
    void deletePhoto_providerRole_returns403() throws Exception {
        mockAuth("prov-token", "prov-1", Role.PROVIDER);
        mockMvc.perform(delete("/api/v1/customers/me/photo")
                        .header("Authorization", "Bearer prov-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/photo returns 204 No Content for CUSTOMER")
    void deletePhoto_customerRole_returns204() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(delete("/api/v1/customers/me/photo")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isNoContent());

        verify(customerService).deleteProfilePhoto("cust-1");
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/photo with application/json returns 415 UNSUPPORTED_MEDIA_TYPE")
    void uploadPhoto_jsonContentType_returns415() throws Exception {
        mockAuth("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(put("/api/v1/customers/me/photo")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"data\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("Unsupported media type"));
    }
}
