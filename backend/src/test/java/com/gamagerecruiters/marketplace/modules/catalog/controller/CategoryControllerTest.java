package com.gamagerecruiters.marketplace.modules.catalog.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;

@WebMvcTest(CategoryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @Test
    @DisplayName("GET /api/v1/categories is public and returns 200 with category tree")
    void getCategories_isPublicAndReturnsTree() throws Exception {
        CategoryResponse sub = new CategoryResponse(
                "sub-1", "Electrical", "electrical", "Electrical work", "bolt", "root-1", new BigDecimal("0.10"), true, Instant.now(), Instant.now());
        CategoryTreeResponse tree = new CategoryTreeResponse(
                "root-1", "Home Repair", "home-repair", "Repairs", "wrench", new BigDecimal("0.10"), true, List.of(sub));

        when(categoryService.getActiveCategoryTree()).thenReturn(List.of(tree));

        mockMvc.perform(get("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Home Repair"))
                .andExpect(jsonPath("$[0].subcategories[0].name").value("Electrical"));
    }

    @Test
    @DisplayName("GET /api/v1/categories/{slug} is public and returns 200 with category details")
    void getCategoryBySlug_isPublicAndReturnsCategory() throws Exception {
        CategoryResponse cat = new CategoryResponse(
                "cat-1", "Cleaning", "cleaning", "Cleaning work", "spray", null, new BigDecimal("0.10"), true, Instant.now(), Instant.now());

        when(categoryService.getCategoryBySlug("cleaning")).thenReturn(cat);

        mockMvc.perform(get("/api/v1/categories/cleaning")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cleaning"))
                .andExpect(jsonPath("$.slug").value("cleaning"));
    }
}

