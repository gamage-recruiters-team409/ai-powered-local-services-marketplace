package com.gamagerecruiters.marketplace.modules.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;

@Repository
public interface AuthTokenRepository extends MongoRepository<AuthToken, String>, AuthTokenRepositoryCustom {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    List<AuthToken> findByUserId(String userId);

    Optional<AuthToken> findFirstByUserIdAndTypeOrderByCreatedAtDesc(String userId, TokenType type);
}
