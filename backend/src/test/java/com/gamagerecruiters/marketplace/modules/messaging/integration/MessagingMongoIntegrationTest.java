package com.gamagerecruiters.marketplace.modules.messaging.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;
import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePollingResponse;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;
import com.gamagerecruiters.marketplace.modules.messaging.service.MessageService;

/**
 * Real MongoDB coverage for compatibility with messages persisted before optimistic locking.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class MessagingMongoIntegrationTest {

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
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageService messageService;

    @MockitoBean
    private BookingFacade bookingFacade;

    @BeforeEach
    void cleanData() {
        mongoTemplate.remove(new Query(), Message.class);
        mongoTemplate.remove(new Query(), Conversation.class);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Real MongoDB: a legacy message without version can be hidden without losing data")
    void legacyMessageCanBeHiddenAfterVersionInitialization() {
        String messageId = insertLegacyMessage("legacy-message-hide", false);

        Message loadedLegacy = messageRepository.findById(messageId).orElseThrow();
        assertThat(loadedLegacy.getVersion()).isNull();
        loginAs("moderator-1", Role.MODERATOR);

        messageService.hideMessage(messageId, "moderator-1", "Policy violation");

        Message stored = messageRepository.findById(messageId).orElseThrow();
        assertThat(stored.getId()).isEqualTo(messageId);
        assertThat(stored.getBody()).isEqualTo("Legacy message content");
        assertThat(stored.getSenderId()).isEqualTo("customer-1");
        assertThat(stored.getCreatedAt()).isEqualTo(legacyCreatedAt());
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.HIDDEN);
        assertThat(stored.getHideReason()).isEqualTo("Policy violation");
        assertThat(stored.getHiddenBy()).isEqualTo("moderator-1");
        assertThat(stored.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("Real MongoDB: a legacy message without version can be flagged without losing data")
    void legacyMessageCanBeFlaggedAfterVersionInitialization() {
        String messageId = insertLegacyMessage("legacy-message-flag", false);

        messageService.flagMessage(messageId, FlagSource.USER_REPORT);

        Message stored = messageRepository.findById(messageId).orElseThrow();
        assertLegacyContentPreserved(stored, messageId);
        assertThat(stored.isFlagged()).isTrue();
        assertThat(stored.getFlagSource()).isEqualTo(FlagSource.USER_REPORT);
        assertThat(stored.getFlaggedAt()).isNotNull();
    }

    @Test
    @DisplayName("Real MongoDB: a legacy message without version can have its flag cleared without losing data")
    void legacyMessageCanHaveFlagClearedAfterVersionInitialization() {
        String messageId = insertLegacyMessage("legacy-message-clear", true);
        loginAs("moderator-1", Role.MODERATOR);

        messageService.clearFlag(messageId);

        Message stored = messageRepository.findById(messageId).orElseThrow();
        assertLegacyContentPreserved(stored, messageId);
        assertThat(stored.isFlagged()).isFalse();
        assertThat(stored.getFlagSource()).isNull();
        assertThat(stored.getFlaggedAt()).isNull();
    }

    @Test
    @DisplayName("Real MongoDB: ObjectId cursor polling returns same-timestamp messages without gaps or duplicates")
    void generatedObjectIdCursorPollingDoesNotSkipOrDuplicateSameTimestampMessages() {
        Instant timestamp = Instant.parse("2026-11-20T08:30:00Z");
        Conversation conversation = conversation("conversation-object-id", "booking-object-id");
        conversationRepository.save(conversation);
        List<Message> messages = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> message(null, conversation.getId(), timestamp))
                .toList();
        List<Message> saved = messageRepository.saveAll(messages);
        assertThat(saved).allSatisfy(message -> assertThat(org.bson.types.ObjectId.isValid(message.getId())).isTrue());

        loginAs("customer-1", Role.CUSTOMER);
        when(bookingFacade.getBookingParticipants("booking-object-id"))
                .thenReturn(participants("booking-object-id"));

        MessagePollingResponse first = messageService.listMessagesSince(
                "booking-object-id", timestamp.minusMillis(1), null);
        MessagePollingResponse second = messageService.listMessagesSince(
                "booking-object-id", first.nextSince(), first.nextMessageId());

        assertThat(first.content()).hasSize(100);
        assertThat(first.totalElements()).isEqualTo(101);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.content()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        List<String> combinedIds = java.util.stream.Stream.concat(first.content().stream(), second.content().stream())
                .map(message -> message.id())
                .toList();
        assertThat(combinedIds).hasSize(101).doesNotHaveDuplicates().isSorted();
        assertThat(combinedIds).containsExactlyInAnyOrderElementsOf(
                saved.stream().map(Message::getId).toList());
    }

    private static Conversation conversation(String id, String bookingId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setChatKey(bookingId);
        conversation.setBookingId(bookingId);
        conversation.setCustomerId("customer-1");
        conversation.setProviderId("provider-1");
        conversation.setCreatedAt(Instant.parse("2026-11-20T08:00:00Z"));
        conversation.setUpdatedAt(Instant.parse("2026-11-20T08:00:00Z"));
        return conversation;
    }

    private static Message message(String id, String conversationId, Instant createdAt) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setSenderId("customer-1");
        message.setBody("message body");
        message.setStatus(MessageStatus.VISIBLE);
        message.setCreatedAt(createdAt);
        return message;
    }

    private String insertLegacyMessage(String id, boolean flagged) {
        Document document = new Document("_id", id)
                .append("conversationId", "conversation-1")
                .append("senderId", "customer-1")
                .append("body", "Legacy message content")
                .append("status", "VISIBLE")
                .append("flagged", flagged)
                .append("createdAt", java.util.Date.from(legacyCreatedAt()));
        if (flagged) {
            document.append("flagSource", "USER_REPORT")
                    .append("flaggedAt", java.util.Date.from(legacyCreatedAt()));
        }
        mongoTemplate.getCollection("messages").insertOne(document);
        return id;
    }

    private static Instant legacyCreatedAt() {
        return Instant.parse("2026-11-20T08:00:00Z");
    }

    private static void assertLegacyContentPreserved(Message stored, String messageId) {
        assertThat(stored.getId()).isEqualTo(messageId);
        assertThat(stored.getBody()).isEqualTo("Legacy message content");
        assertThat(stored.getSenderId()).isEqualTo("customer-1");
        assertThat(stored.getConversationId()).isEqualTo("conversation-1");
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.VISIBLE);
        assertThat(stored.getCreatedAt()).isEqualTo(legacyCreatedAt());
        assertThat(stored.getVersion()).isNotNull();
    }

    private static BookingParticipants participants(String bookingId) {
        return new BookingParticipants(bookingId, null, "listing-1", "customer-1", "provider-1",
                BookingStatus.CONFIRMED, null, null);
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
