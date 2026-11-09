package com.gamagerecruiters.marketplace.modules.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;

@Repository
public class AuthTokenRepositoryCustomImpl implements AuthTokenRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public AuthTokenRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<AuthToken> findAndConsumeValidToken(String tokenHash, Instant now) {
        Query query = new Query(
                Criteria.where("tokenHash").is(tokenHash)
                        .and("type").is(TokenType.REFRESH)
                        .and("usedAt").isNull()
                        .and("revokedAt").isNull()
                        .and("expiresAt").gt(now)
        );

        Update update = new Update().set("usedAt", now);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

        AuthToken updated = mongoTemplate.findAndModify(query, update, options, AuthToken.class);
        return Optional.ofNullable(updated);
    }

    @Override
    public void revokeAllActiveTokensByUserId(String userId) {
        Query query = new Query(
                Criteria.where("userId").is(userId)
                        .and("revokedAt").isNull()
        );
        Update update = new Update().set("revokedAt", Instant.now());
        mongoTemplate.updateMulti(query, update, AuthToken.class);
    }
}
