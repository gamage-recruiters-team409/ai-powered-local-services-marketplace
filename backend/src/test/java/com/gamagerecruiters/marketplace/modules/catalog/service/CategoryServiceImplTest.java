package com.gamagerecruiters.marketplace.modules.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryTreeResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.repository.CategoryRepository;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryServiceImpl categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryServiceImpl(categoryRepository);
    }

    @Test
    @DisplayName("getActiveCategoryTree returns roots with their respective active subcategories")
    void getActiveCategoryTree_returnsHierarchicalTree() {
        Category root = new Category("Home Repair", "home-repair", null, new BigDecimal("0.10"));
        root.setId("root-1");

        Category sub = new Category("Electrical", "electrical", "root-1", new BigDecimal("0.10"));
        sub.setId("sub-1");

        when(categoryRepository.findByParentIdIsNullAndActiveTrue()).thenReturn(List.of(root));
        when(categoryRepository.findByParentIdAndActiveTrue("root-1")).thenReturn(List.of(sub));

        List<CategoryTreeResponse> result = categoryService.getActiveCategoryTree();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Home Repair");
        assertThat(result.get(0).subcategories()).hasSize(1);
        assertThat(result.get(0).subcategories().get(0).name()).isEqualTo("Electrical");
    }

    @Test
    @DisplayName("getCategoryBySlug returns category when active")
    void getCategoryBySlug_whenFoundAndActive_returnsCategory() {
        Category cat = new Category("Plumbing", "plumbing", null, new BigDecimal("0.12"));
        cat.setId("cat-1");

        when(categoryRepository.findBySlug("plumbing")).thenReturn(Optional.of(cat));

        CategoryResponse response = categoryService.getCategoryBySlug("plumbing");

        assertThat(response.id()).isEqualTo("cat-1");
        assertThat(response.slug()).isEqualTo("plumbing");
        assertThat(response.commissionRate()).isEqualTo(new BigDecimal("0.12"));
    }

    @Test
    @DisplayName("getCategoryBySlug throws ResourceNotFoundException when slug does not exist")
    void getCategoryBySlug_whenNotFound_throwsException() {
        when(categoryRepository.findBySlug("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.getCategoryBySlug("unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("createCategory creates root category with default 10% commission when commissionRate is null")
    void createCategory_rootWithDefaultCommission() {
        CreateCategoryRequest request = new CreateCategoryRequest(
                "Cleaning", "cleaning", "Cleaning services", "spray-can", null, null);

        when(categoryRepository.existsBySlug("cleaning")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId("generated-id");
            return c;
        });

        CategoryResponse response = categoryService.createCategory(request);

        assertThat(response.id()).isEqualTo("generated-id");
        assertThat(response.slug()).isEqualTo("cleaning");
        assertThat(response.commissionRate()).isEqualTo(new BigDecimal("0.10"));
        assertThat(response.active()).isTrue();
    }

    @Test
    @DisplayName("createCategory throws ResourceConflictException when slug already exists")
    void createCategory_whenDuplicateSlug_throwsConflict() {
        CreateCategoryRequest request = new CreateCategoryRequest(
                "Cleaning", "cleaning", null, null, null, null);

        when(categoryRepository.existsBySlug("cleaning")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createCategory creates subcategory when valid root parent exists")
    void createCategory_asSubcategory() {
        Category parent = new Category("Home Repair", "home-repair", null, new BigDecimal("0.10"));
        parent.setId("parent-id");

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Carpentry", "carpentry", null, null, "parent-id", new BigDecimal("0.15"));

        when(categoryRepository.existsBySlug("carpentry")).thenReturn(false);
        when(categoryRepository.findById("parent-id")).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId("sub-id");
            return c;
        });

        CategoryResponse response = categoryService.createCategory(request);

        assertThat(response.id()).isEqualTo("sub-id");
        assertThat(response.parentId()).isEqualTo("parent-id");
        assertThat(response.commissionRate()).isEqualTo(new BigDecimal("0.15"));
    }

    @Test
    @DisplayName("createCategory throws ResourceConflictException when parent is already a subcategory")
    void createCategory_nestedSubcategory_throwsConflict() {
        Category parent = new Category("Subcat", "subcat", "root-id", new BigDecimal("0.10"));
        parent.setId("parent-id");

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Third Tier", "third-tier", null, null, "parent-id", null);

        when(categoryRepository.existsBySlug("third-tier")).thenReturn(false);
        when(categoryRepository.findById("parent-id")).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> categoryService.createCategory(request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("maximum 2 tiers");
    }

    @Test
    @DisplayName("updateCategory updates fields successfully")
    void updateCategory_updatesFields() {
        Category existing = new Category("Old Name", "old-slug", null, new BigDecimal("0.10"));
        existing.setId("cat-1");

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "New Name", "new-slug", "New Desc", "new-icon", new BigDecimal("0.08"), true);

        when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.existsBySlugAndIdNot("new-slug", "cat-1")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse response = categoryService.updateCategory("cat-1", request);

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.slug()).isEqualTo("new-slug");
        assertThat(response.commissionRate()).isEqualTo(new BigDecimal("0.08"));
    }

    @Test
    @DisplayName("deactivateCategory soft-deactivates category when no active subcategories")
    void deactivateCategory_setsActiveFalse() {
        Category existing = new Category("Category", "cat", null, new BigDecimal("0.10"));
        existing.setId("cat-1");
        existing.setActive(true);

        when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByParentIdAndActiveTrue("cat-1")).thenReturn(false);

        categoryService.deactivateCategory("cat-1");

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivateCategory throws ResourceConflictException if category has active subcategories")
    void deactivateCategory_withActiveSubcategories_throwsConflict() {
        Category existing = new Category("Category", "cat", null, new BigDecimal("0.10"));
        existing.setId("cat-1");

        when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByParentIdAndActiveTrue("cat-1")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deactivateCategory("cat-1"))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("active subcategories");
    }

    @Test
    @DisplayName("updateCategory with active=false throws ResourceConflictException if category has active subcategories")
    void updateCategory_deactivatingWithActiveSubcategories_throwsConflict() {
        Category existing = new Category("Parent Cat", "parent-cat", null, new BigDecimal("0.10"));
        existing.setId("parent-1");
        existing.setActive(true);

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Parent Cat", "parent-cat", null, null, new BigDecimal("0.10"), false);

        when(categoryRepository.findById("parent-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.existsBySlugAndIdNot("parent-cat", "parent-1")).thenReturn(false);
        when(categoryRepository.existsByParentIdAndActiveTrue("parent-1")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory("parent-1", request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot deactivate category while it has active subcategories");
    }

    @Test
    @DisplayName("createCategory throws ResourceConflictException when parent category is inactive")
    void createCategory_underInactiveParent_throwsConflict() {
        Category inactiveParent = new Category("Inactive Parent", "inactive-parent", null, new BigDecimal("0.10"));
        inactiveParent.setId("parent-inactive");
        inactiveParent.setActive(false);

        CreateCategoryRequest request = new CreateCategoryRequest(
                "Sub Category", "sub-category", null, null, "parent-inactive", null);

        when(categoryRepository.existsBySlug("sub-category")).thenReturn(false);
        when(categoryRepository.findById("parent-inactive")).thenReturn(Optional.of(inactiveParent));

        assertThatThrownBy(() -> categoryService.createCategory(request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot create subcategory under an inactive parent category");
    }

    @Test
    @DisplayName("updateCategory throws ResourceConflictException when activating subcategory under inactive parent")
    void updateCategory_activatingUnderInactiveParent_throwsConflict() {
        Category inactiveParent = new Category("Inactive Parent", "inactive-parent", null, new BigDecimal("0.10"));
        inactiveParent.setId("parent-inactive");
        inactiveParent.setActive(false);

        Category subCategory = new Category("Sub Category", "sub-category", "parent-inactive", new BigDecimal("0.10"));
        subCategory.setId("sub-1");
        subCategory.setActive(false);

        UpdateCategoryRequest request = new UpdateCategoryRequest(
                "Sub Category", "sub-category", null, null, new BigDecimal("0.10"), true);

        when(categoryRepository.findById("sub-1")).thenReturn(Optional.of(subCategory));
        when(categoryRepository.existsBySlugAndIdNot("sub-category", "sub-1")).thenReturn(false);
        when(categoryRepository.findById("parent-inactive")).thenReturn(Optional.of(inactiveParent));

        assertThatThrownBy(() -> categoryService.updateCategory("sub-1", request))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot activate subcategory while its parent category is inactive");
    }
}

