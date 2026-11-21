package com.gamagerecruiters.marketplace.modules.messaging.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByChatKey(String chatKey);
}
