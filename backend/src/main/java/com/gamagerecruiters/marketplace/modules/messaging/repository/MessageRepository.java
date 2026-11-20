package com.gamagerecruiters.marketplace.modules.messaging.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;

public interface MessageRepository extends MongoRepository<Message, String> {

    Page<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    Page<Message> findByConversationIdAndCreatedAtAfter(
            String conversationId, java.time.Instant since, Pageable pageable);

    /**
     * The cursor id is Object so production ObjectIds remain BSON ObjectIds in the query while
     * legacy and test String identifiers continue to compare as Strings.
     */
    @Query("{ 'conversationId': ?0, '$or': [ { 'createdAt': { '$gt': ?1 } }, "
            + "{ 'createdAt': ?1, '_id': { '$gt': ?2 } } ] }")
    Page<Message> findAfterCursor(String conversationId, java.time.Instant since, Object afterId,
                                  Pageable pageable);
}
