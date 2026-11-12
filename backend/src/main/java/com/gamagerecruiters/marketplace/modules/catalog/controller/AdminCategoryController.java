package com.gamagerecruiters.marketplace.modules.catalog.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/categories")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * View all categories for admin panel.
     * Both ADMIN and SUPER_ADMIN (read-only) have view access per AdminAccessPolicyService.
     */
    @GetMapping
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'ADMIN')")
    public ResponseEntity<List<CategoryTreeResponse>> getAllCategories() {
        return ResponseEntity.ok(categoryService.getAllCategoriesForAdmin());
    }

    /**
     * Create a new category.
     * Restricted strictly to ADMIN role per least-privilege admin access matrix.
     */
    @PostMapping
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'ADMIN')")
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = categoryService.createCategory(request);
        URI location = URI.create(ApiPaths.API_V1 + "/categories/" + created.slug());
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Update category details.
     * Restricted strictly to ADMIN role per least-privilege admin access matrix.
     */
    @PutMapping("/{id}")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'ADMIN')")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable String id,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return ResponseEntity.ok(categoryService.updateCategory(id, request));
    }

    /**
     * Soft-deactivate a category.
     * Restricted strictly to ADMIN role per least-privilege admin access matrix.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'ADMIN')")
    public ResponseEntity<Void> deactivateCategory(@PathVariable String id) {
        categoryService.deactivateCategory(id);
        return ResponseEntity.noContent().build();
    }
}

