package com.gamagerecruiters.marketplace.modules.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CreateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.dto.UpdateCategoryRequest;
import com.gamagerecruiters.marketplace.modules.catalog.initializer.CategoryIndexInitializer;
import com.gamagerecruiters.marketplace.modules.catalog.repository.CategoryRepository;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Runs catalog index initialization and category business rules against a real MongoDB container:
 * stored index definitions, fresh-database startup initialization, incompatible index fail-fast,
 * duplicate slug rejection, and deactivation / inactive-parent safeguards.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class CategoryMongoIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CategoryMongoIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer != null && mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryIndexInitializer categoryIndexInitializer;

    @BeforeEach
    void cleanDocuments() {
        mongoTemplate.remove(new Query(), Category.class);
    }

    @Test
    @DisplayName("Real MongoDB startup stores required slug_1 (unique, non-sparse) and parentId_1 indexes")
    void storedCategoryIndexesMatchContract() {
        assertStoredCategoryIndexes();
    }

    @Test
    @DisplayName("Fresh database: dropping categories and running initializer creates collection and both indexes")
    void freshDatabaseCreatesCollectionAndIndexes() {
        mongoTemplate.dropCollection("categories");
        assertThat(mongoTemplate.collectionExists("categories")).isFalse();

        assertThatCode(() -> categoryIndexInitializer.initializeIndexes()).doesNotThrowAnyException();

        assertThat(mongoTemplate.collectionExists("categories")).isTrue();
        assertStoredCategoryIndexes();
    }

    @Test
    @DisplayName("Real MongoDB unique index slug_1 rejects duplicate category slugs at database level")
    void duplicateCategorySlugIsRejectedByUniqueIndex() {
        mongoTemplate.insert(new Category("Electrical Services", "electrical-services", null, new BigDecimal("0.10")));

        assertThatThrownBy(() -> mongoTemplate.insert(
                new Category("Electrical Duplicate", "electrical-services", null, new BigDecimal("0.12"))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(categoryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("CategoryService rejects duplicate slug creation against real MongoDB")
    void createCategoryRejectsDuplicateSlugEndToEnd() {
        categoryService.createCategory(new CreateCategoryRequest(
                "Plumbing", "plumbing", "Plumbing services", "wrench", null, new BigDecimal("0.10")));

        assertThatThrownBy(() -> categoryService.createCategory(new CreateCategoryRequest(
                "Plumbing Copy", "plumbing", "Duplicate", "wrench", null, new BigDecimal("0.10"))))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Incompatible non-unique slug_1 index on categories fails startup verification and is preserved")
    void incompatibleExistingSlugIndexFailsFastAndIsPreserved() {
        try {
            mongoTemplate.dropCollection("categories");
            mongoTemplate.createCollection("categories");
            mongoTemplate.getCollection("categories").createIndex(
                    Indexes.ascending("slug"),
                    new IndexOptions().unique(false).name("slug_1"));

            assertThatThrownBy(() -> categoryIndexInitializer.initializeIndexes())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Incompatible index 'slug_1' detected on categories");

            Document stillStored = findIndex("slug_1");
            assertThat(stillStored).isNotNull();
            assertThat(stillStored.getBoolean("unique", false)).isFalse();
        } finally {
            // Restore valid schema for subsequent integration tests
            mongoTemplate.dropCollection("categories");
            categoryIndexInitializer.initializeIndexes();
        }
    }

    @Test
    @DisplayName("Deactivation safeguard and inactive-parent rules work end-to-end against real MongoDB")
    void deactivationSafeguardAndInactiveParentRulesWorkOnRealMongo() {
        CategoryResponse parent = categoryService.createCategory(new CreateCategoryRequest(
                "Home Repair", "home-repair", "General repairs", "wrench", null, new BigDecimal("0.10")));
        CategoryResponse child = categoryService.createCategory(new CreateCategoryRequest(
                "Roofing", "roofing", "Roofing repair", "home", parent.id(), new BigDecimal("0.10")));

        UpdateCategoryRequest deactivateParentPut = new UpdateCategoryRequest(
                "Home Repair", "home-repair", "General repairs", "wrench", new BigDecimal("0.10"), false);

        assertThatThrownBy(() -> categoryService.updateCategory(parent.id(), deactivateParentPut))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot deactivate category while it has active subcategories");

        assertThatThrownBy(() -> categoryService.deactivateCategory(parent.id()))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot deactivate category while it has active subcategories");

        categoryService.deactivateCategory(child.id());
        categoryService.deactivateCategory(parent.id());

        assertThatThrownBy(() -> categoryService.createCategory(new CreateCategoryRequest(
                "Painting", "painting", "Wall painting", "brush", parent.id(), new BigDecimal("0.10"))))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot create subcategory under an inactive parent category");

        UpdateCategoryRequest reactivateChildPut = new UpdateCategoryRequest(
                "Roofing", "roofing", "Roofing repair", "home", new BigDecimal("0.10"), true);

        assertThatThrownBy(() -> categoryService.updateCategory(child.id(), reactivateChildPut))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Cannot activate subcategory while its parent category is inactive");
    }

    private void assertStoredCategoryIndexes() {
        List<Document> storedIndexes = mongoTemplate.getCollection("categories").listIndexes().into(new ArrayList<>());
        for (Document indexDoc : storedIndexes) {
            log.info("Stored index on categories: {}", indexDoc.toJson());
        }

        Document slugIndex = findIndex(storedIndexes, "slug_1");
        assertThat(slugIndex).isNotNull();
        assertThat(new ArrayList<>(slugIndex.get("key", Document.class).keySet())).containsExactly("slug");
        assertThat(slugIndex.get("key", Document.class).getInteger("slug")).isEqualTo(1);
        assertThat(slugIndex.getBoolean("unique", false)).isTrue();
        assertThat(slugIndex.getBoolean("sparse", false)).isFalse();
        assertThat(slugIndex.containsKey("partialFilterExpression")).isFalse();

        Document parentIdIndex = findIndex(storedIndexes, "parentId_1");
        assertThat(parentIdIndex).isNotNull();
        assertThat(new ArrayList<>(parentIdIndex.get("key", Document.class).keySet())).containsExactly("parentId");
        assertThat(parentIdIndex.get("key", Document.class).getInteger("parentId")).isEqualTo(1);
        assertThat(parentIdIndex.getBoolean("unique", false)).isFalse();
        assertThat(parentIdIndex.getBoolean("sparse", false)).isFalse();
        assertThat(parentIdIndex.containsKey("partialFilterExpression")).isFalse();
    }

    private Document findIndex(String name) {
        List<Document> indexes = mongoTemplate.getCollection("categories").listIndexes().into(new ArrayList<>());
        return findIndex(indexes, name);
    }

    private static Document findIndex(List<Document> indexes, String name) {
        return indexes.stream()
                .filter(doc -> name.equals(doc.getString("name")))
                .findFirst()
                .orElse(null);
    }
}

