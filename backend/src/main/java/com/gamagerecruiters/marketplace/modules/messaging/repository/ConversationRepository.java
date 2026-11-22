package com.gamagerecruiters.marketplace.modules.messaging.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByChatKey(String chatKey);

    /**
     * Callers must pass a Pageable sorted by updatedAt DESC and id DESC for deterministic pages.
     */
    Page<Conversation> findByCustomerIdOrProviderId(
            String customerId, String providerId, Pageable pageable);
}
