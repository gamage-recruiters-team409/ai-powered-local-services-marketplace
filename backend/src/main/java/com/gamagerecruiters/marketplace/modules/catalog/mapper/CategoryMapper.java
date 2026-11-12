package com.gamagerecruiters.marketplace.modules.catalog.mapper;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;

public final class CategoryMapper {

    private CategoryMapper() {
    }

    public static CategoryResponse toResponse(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getIcon(),
                category.getParentId(),
                category.getCommissionRate(),
                category.isActive(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    public static CategoryTreeResponse toTreeResponse(Category category, List<CategoryResponse> subcategories) {
        if (category == null) {
            return null;
        }
        return new CategoryTreeResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getIcon(),
                category.getCommissionRate(),
                category.isActive(),
                subcategories != null ? subcategories : List.of()
        );
    }
}

