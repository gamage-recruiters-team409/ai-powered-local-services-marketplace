package com.gamagerecruiters.marketplace.modules.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;

@WebMvcTest(AdminCategoryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    private void mockAdminToken(String token, String adminId) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(adminId);
        when(tokenService.getRoleFromToken(token)).thenReturn(Role.ADMIN);

        User admin = new User();
        admin.setId(adminId);
        admin.setEmail("admin@test.local");
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(adminId)).thenReturn(admin);
    }

    private void mockCustomerToken(String token, String customerId) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(customerId);
        when(tokenService.getRoleFromToken(token)).thenReturn(Role.CUSTOMER);

        User customer = new User();
        customer.setId(customerId);
        customer.setEmail("customer@test.local");
        customer.setRole(Role.CUSTOMER);
        customer.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(customerId)).thenReturn(customer);
    }

    private void mockSuperAdminToken(String token, String superAdminId) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(superAdminId);
        when(tokenService.getRoleFromToken(token)).thenReturn(Role.SUPER_ADMIN);

        User superAdmin = new User();
        superAdmin.setId(superAdminId);
        superAdmin.setEmail("superadmin@test.local");
        superAdmin.setRole(Role.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(superAdminId)).thenReturn(superAdmin);
    }

    @Test
    @DisplayName("Admin endpoint without token returns 401 UNAUTHORIZED")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Admin endpoint with CUSTOMER role returns 403 ACCESS_DENIED")
    void shouldReturn403WhenCustomerRole() throws Exception {
        mockCustomerToken("cust-token", "cust-1");

        mockMvc.perform(get("/api/v1/admin/categories")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("ADMIN role can GET all categories (returns 200)")
    void shouldAllowAdminToGetAllCategories() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        CategoryTreeResponse root = new CategoryTreeResponse(
                "cat-1", "Plumbing", "plumbing", "Plumbing desc", "wrench", new BigDecimal("0.10"), true, List.of());
        when(categoryService.getAllCategoriesForAdmin()).thenReturn(List.of(root));

        mockMvc.perform(get("/api/v1/admin/categories")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Plumbing"));
    }

    @Test
    @DisplayName("ADMIN role can POST create category (returns 201 with Location header)")
    void shouldAllowAdminToCreateCategory() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Roofing", "roofing", "Roofing work", "home", null, new BigDecimal("0.12"));
        CategoryResponse response = new CategoryResponse(
                "cat-roof", "Roofing", "roofing", "Roofing work", "home", null, new BigDecimal("0.12"), true, Instant.now(), Instant.now());

        when(categoryService.createCategory(any(CreateCategoryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/categories/roofing"))
                .andExpect(jsonPath("$.id").value("cat-roof"))
                .andExpect(jsonPath("$.name").value("Roofing"));
    }

    @Test
    @DisplayName("POST create category with invalid payload returns 400 VALIDATION_FAILED")
    void shouldReturn400OnInvalidPayload() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        // Blank name and invalid slug
        CreateCategoryRequest invalidRequest = new CreateCategoryRequest(
                "", "INVALID SLUG!", null, null, null, new BigDecimal("0.10"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("ADMIN role can PUT update category (returns 200)")
    void shouldAllowAdminToUpdateCategory() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Updated Roofing", "roofing", "Updated desc", "home", new BigDecimal("0.15"), true);
        CategoryResponse response = new CategoryResponse(
                "cat-roof", "Updated Roofing", "roofing", "Updated desc", "home", null, new BigDecimal("0.15"), true, Instant.now(), Instant.now());

        when(categoryService.updateCategory(eq("cat-roof"), any(UpdateCategoryRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/admin/categories/cat-roof")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Roofing"));
    }

    @Test
    @DisplayName("ADMIN role can DELETE deactivate category (returns 204 No Content)")
    void shouldAllowAdminToDeactivateCategory() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        doNothing().when(categoryService).deactivateCategory("cat-roof");

        mockMvc.perform(delete("/api/v1/admin/categories/cat-roof")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("SUPER_ADMIN role can GET all categories (view-only access per AdminAccessPolicyService, returns 200)")
    void shouldAllowSuperAdminToGetAllCategories() throws Exception {
        mockSuperAdminToken("super-token", "super-1");

        CategoryTreeResponse root = new CategoryTreeResponse(
                "cat-1", "Plumbing", "plumbing", "Plumbing desc", "wrench", new BigDecimal("0.10"), true, List.of());
        when(categoryService.getAllCategoriesForAdmin()).thenReturn(List.of(root));

        mockMvc.perform(get("/api/v1/admin/categories")
                        .header("Authorization", "Bearer super-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Plumbing"));
    }

    @Test
    @DisplayName("SUPER_ADMIN role cannot POST create category (least-privilege restriction, returns 403 Forbidden)")
    void shouldForbidSuperAdminFromCreatingCategory() throws Exception {
        mockSuperAdminToken("super-token", "super-1");

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Roofing", "roofing", "Roofing work", "home", null, new BigDecimal("0.12"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer super-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("SUPER_ADMIN role cannot PUT update category (least-privilege restriction, returns 403 Forbidden)")
    void shouldForbidSuperAdminFromUpdatingCategory() throws Exception {
        mockSuperAdminToken("super-token", "super-1");

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Updated Roofing", "roofing", "Updated desc", "home", new BigDecimal("0.15"), true);

        mockMvc.perform(put("/api/v1/admin/categories/cat-roof")
                        .header("Authorization", "Bearer super-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("SUPER_ADMIN role cannot DELETE deactivate category (least-privilege restriction, returns 403 Forbidden)")
    void shouldForbidSuperAdminFromDeactivatingCategory() throws Exception {
        mockSuperAdminToken("super-token", "super-1");

        mockMvc.perform(delete("/api/v1/admin/categories/cat-roof")
                        .header("Authorization", "Bearer super-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("PUT update with active=false when category has active subcategories returns 409 RESOURCE_CONFLICT")
    void shouldReturn409WhenDeactivatingParentViaPutWithActiveSubcategories() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Home Repair", "home-repair", "General repairs", "wrench", new BigDecimal("0.10"), false);
        when(categoryService.updateCategory(eq("parent-1"), any(UpdateCategoryRequest.class)))
                .thenThrow(new ResourceConflictException("Cannot deactivate category while it has active subcategories"));

        mockMvc.perform(put("/api/v1/admin/categories/parent-1")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("DELETE deactivate when category has active subcategories returns 409 RESOURCE_CONFLICT")
    void shouldReturn409WhenDeactivatingParentViaDeleteWithActiveSubcategories() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        doThrow(new ResourceConflictException("Cannot deactivate category while it has active subcategories"))
                .when(categoryService).deactivateCategory("parent-1");

        mockMvc.perform(delete("/api/v1/admin/categories/parent-1")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("POST create subcategory under inactive parent returns 409 RESOURCE_CONFLICT")
    void shouldReturn409WhenCreatingSubcategoryUnderInactiveParent() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Sub Category", "sub-category", "Desc", "icon", "inactive-parent", new BigDecimal("0.10"));
        when(categoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenThrow(new ResourceConflictException("Cannot create subcategory under an inactive parent category"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("PUT reactivate subcategory under inactive parent returns 409 RESOURCE_CONFLICT")
    void shouldReturn409WhenReactivatingSubcategoryUnderInactiveParent() throws Exception {
        mockAdminToken("admin-token", "admin-1");

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Sub Category", "sub-category", "Desc", "icon", new BigDecimal("0.10"), true);
        when(categoryService.updateCategory(eq("sub-1"), any(UpdateCategoryRequest.class)))
                .thenThrow(new ResourceConflictException("Cannot activate subcategory while its parent category is inactive"));

        mockMvc.perform(put("/api/v1/admin/categories/sub-1")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }
}

