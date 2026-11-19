package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;

/**
 * Adapter connecting the listing module's {@link CategoryLookupPort} to the catalog module's
 * public {@link CategoryService} without injecting foreign repositories.
 */
@Component
public class CatalogCategoryLookupAdapter implements CategoryLookupPort {

    private final CategoryService categoryService;

    public CatalogCategoryLookupAdapter(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Override
    public Optional<CategorySnapshot> findById(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return Optional.empty();
        }
        try {
            CategoryResponse response = categoryService.getCategoryById(categoryId);
            return Optional.of(new CategorySnapshot(
                    response.id(),
                    response.name(),
                    response.parentId(),
                    response.active(),
                    response.commissionRate()
            ));
        } catch (ResourceNotFoundException ex) {
            return Optional.empty();
        }
    }
}

