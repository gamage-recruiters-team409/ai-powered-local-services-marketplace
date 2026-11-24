package com.gamagerecruiters.marketplace.modules.listing.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
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
    public List<CategorySnapshot> findActiveCategories() {
        List<CategorySnapshot> active = new ArrayList<>();
        for (CategoryTreeResponse root : categoryService.getActiveCategoryTree()) {
            if (root.active() && hasId(root.id())) {
                active.add(toSnapshot(root.id(), root.name(), null, root.active(), root.commissionRate()));
            }
            if (root.subcategories() == null) {
                continue;
            }
            for (CategoryResponse subcategory : root.subcategories()) {
                if (subcategory.active() && hasId(subcategory.id())) {
                    active.add(toSnapshot(
                            subcategory.id(), subcategory.name(), root.id(), true, subcategory.commissionRate()));
                }
            }
        }
        return active;
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

    private static CategorySnapshot toSnapshot(
            String id,
            String name,
            String parentId,
            boolean active,
            BigDecimal commissionRate
    ) {
        return new CategorySnapshot(id, name, parentId, active, commissionRate);
    }

    private static boolean hasId(String id) {
        return id != null && !id.isBlank();
    }
}

