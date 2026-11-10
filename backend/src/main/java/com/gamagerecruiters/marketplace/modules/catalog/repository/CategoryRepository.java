package com.gamagerecruiters.marketplace.modules.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;

@Repository
public interface CategoryRepository extends MongoRepository<Category, String> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);

    List<Category> findByActiveTrue();

    List<Category> findByParentIdIsNullAndActiveTrue();

    List<Category> findByParentIdAndActiveTrue(String parentId);

    List<Category> findByParentIdIsNull();

    List<Category> findByParentId(String parentId);

    boolean existsByParentIdAndActiveTrue(String parentId);
}

