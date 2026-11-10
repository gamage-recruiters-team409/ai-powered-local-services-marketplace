package com.gamagerecruiters.marketplace.modules.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.mapper.CategoryMapper;
import com.gamagerecruiters.marketplace.modules.catalog.repository.CategoryRepository;

@Service
public class CategoryServiceImpl implements CategoryService {

    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.10");

    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public List<CategoryTreeResponse> getActiveCategoryTree() {
        List<Category> rootCategories = categoryRepository.findByParentIdIsNullAndActiveTrue();
        return rootCategories.stream()
                .map(root -> {
                    List<CategoryResponse> subcategories = categoryRepository.findByParentIdAndActiveTrue(root.getId()).stream()
                            .map(CategoryMapper::toResponse)
                            .toList();
                    return CategoryMapper.toTreeResponse(root, subcategories);
                })
                .toList();
    }

    @Override
    public CategoryResponse getCategoryBySlug(String slug) {
        Category category = categoryRepository.findBySlug(slug)
                .filter(Category::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with slug: " + slug));
        return CategoryMapper.toResponse(category);
    }

    @Override
    public CategoryResponse getCategoryById(String id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
        return CategoryMapper.toResponse(category);
    }

    @Override
    public List<CategoryTreeResponse> getAllCategoriesForAdmin() {
        List<Category> rootCategories = categoryRepository.findByParentIdIsNull();
        return rootCategories.stream()
                .map(root -> {
                    List<CategoryResponse> subcategories = categoryRepository.findByParentId(root.getId()).stream()
                            .map(CategoryMapper::toResponse)
                            .toList();
                    return CategoryMapper.toTreeResponse(root, subcategories);
                })
                .toList();
    }

    @Override
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ResourceConflictException("Category with slug '" + request.slug() + "' already exists");
        }

        String parentId = null;
        if (request.parentId() != null && !request.parentId().isBlank()) {
            Category parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found with id: " + request.parentId()));
            if (parent.getParentId() != null) {
                throw new ResourceConflictException("Subcategories cannot have nested subcategories (maximum 2 tiers supported)");
            }
            parentId = parent.getId();
        }

        BigDecimal commission = request.commissionRate() != null ? request.commissionRate() : DEFAULT_COMMISSION_RATE;

        Category category = new Category();
        category.setName(request.name().trim());
        category.setSlug(request.slug().trim().toLowerCase());
        category.setDescription(request.description() != null ? request.description().trim() : null);
        category.setIcon(request.icon() != null ? request.icon().trim() : null);
        category.setParentId(parentId);
        category.setCommissionRate(commission);
        category.setActive(true);
        category.setCreatedAt(Instant.now());
        category.setUpdatedAt(Instant.now());

        Category saved = categoryRepository.save(category);
        return CategoryMapper.toResponse(saved);
    }

    @Override
    public CategoryResponse updateCategory(String id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        String newSlug = request.slug().trim().toLowerCase();
        if (categoryRepository.existsBySlugAndIdNot(newSlug, id)) {
            throw new ResourceConflictException("Category with slug '" + newSlug + "' already exists");
        }

        category.setName(request.name().trim());
        category.setSlug(newSlug);
        category.setDescription(request.description() != null ? request.description().trim() : null);
        category.setIcon(request.icon() != null ? request.icon().trim() : null);
        if (request.commissionRate() != null) {
            category.setCommissionRate(request.commissionRate());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        category.setUpdatedAt(Instant.now());

        Category updated = categoryRepository.save(category);
        return CategoryMapper.toResponse(updated);
    }

    @Override
    public void deactivateCategory(String id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        if (categoryRepository.existsByParentIdAndActiveTrue(id)) {
            throw new ResourceConflictException("Cannot deactivate category while it has active subcategories");
        }

        category.setActive(false);
        category.setUpdatedAt(Instant.now());
        categoryRepository.save(category);
    }
}

