package com.gamagerecruiters.marketplace.modules.catalog.service;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;

public interface CategoryService {

    /**
     * Public discovery: active root categories with their active subcategories.
     */
    List<CategoryTreeResponse> getActiveCategoryTree();

    /**
     * Public lookup by unique slug.
     */
    CategoryResponse getCategoryBySlug(String slug);

    /**
     * Internal/Public lookup by ID.
     */
    CategoryResponse getCategoryById(String id);

    /**
     * Admin view: full taxonomy tree including inactive categories.
     */
    List<CategoryTreeResponse> getAllCategoriesForAdmin();

    /**
     * Admin operation: create root category or subcategory with commission rate.
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * Admin operation: update category metadata, commission rate, or status.
     */
    CategoryResponse updateCategory(String id, UpdateCategoryRequest request);

    /**
     * Admin operation: soft-deactivate category.
     */
    void deactivateCategory(String id);
}

