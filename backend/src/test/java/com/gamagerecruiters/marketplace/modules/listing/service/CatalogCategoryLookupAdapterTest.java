package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;

@ExtendWith(MockitoExtension.class)
class CatalogCategoryLookupAdapterTest {

    @Mock
    private CategoryService categoryService;

    private CatalogCategoryLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CatalogCategoryLookupAdapter(categoryService);
    }

    @Test
    @DisplayName("Maps CategoryResponse to CategorySnapshot when category exists")
    void mapsExistingCategory() {
        Instant now = Instant.now();
        when(categoryService.getCategoryById("cat-1")).thenReturn(new CategoryResponse(
                "cat-1",
                "Emergency Leak Repair",
                "emergency-leak-repair",
                "Pipe leaks",
                "wrench",
                "cat-root",
                new BigDecimal("0.12"),
                true,
                now,
                now
        ));

        Optional<CategorySnapshot> snapshot = adapter.findById("cat-1");

        assertThat(snapshot).contains(new CategorySnapshot(
                "cat-1",
                "Emergency Leak Repair",
                "cat-root",
                true,
                new BigDecimal("0.12")
        ));
    }

    @Test
    @DisplayName("Returns empty Optional when CategoryService throws ResourceNotFoundException or ID is blank")
    void returnsEmptyOnMissingOrBlankId() {
        when(categoryService.getCategoryById("missing"))
                .thenThrow(new ResourceNotFoundException("Not found"));

        assertThat(adapter.findById("missing")).isEmpty();
        assertThat(adapter.findById("  ")).isEmpty();
        assertThat(adapter.findById(null)).isEmpty();
        verify(categoryService).getCategoryById("missing");
        verifyNoMoreInteractions(categoryService);
    }
}
