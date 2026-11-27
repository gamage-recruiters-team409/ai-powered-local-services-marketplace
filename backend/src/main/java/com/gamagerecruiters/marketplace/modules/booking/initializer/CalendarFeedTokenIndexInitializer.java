package com.gamagerecruiters.marketplace.modules.booking.initializer;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Unique indexes for calendar_feed_tokens: {@code tokenHash} (feed lookup, no two links alike) and {@code userId}
 * (one link per user). Same create-and-verify rules as {@link BookingIndexInitializer}.
 */
@Component
@Profile("!test")
public class CalendarFeedTokenIndexInitializer {

    static final String COLLECTION = "calendar_feed_tokens";

    private final BookingIndexInitializer indexes;

    public CalendarFeedTokenIndexInitializer(MongoTemplate mongoTemplate) {
        this.indexes = new BookingIndexInitializer(mongoTemplate);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        indexes.ensureCollectionExists(COLLECTION);
        indexes.ensureIndex(COLLECTION, List.of("tokenHash"), "tokenHash_1", true);
        indexes.ensureIndex(COLLECTION, List.of("userId"), "userId_1", true);
    }
}
