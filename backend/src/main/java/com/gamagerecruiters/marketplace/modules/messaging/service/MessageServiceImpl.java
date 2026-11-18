package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;

@Service
public class MessageServiceImpl implements MessageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_BODY_LENGTH = 2000;
    private static final Set<BookingStatus> CONTACT_VISIBLE_STATUSES = EnumSet.of(
            BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED, BookingStatus.DISPUTED);
    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);

    private final BookingFacade bookingFacade;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public MessageServiceImpl(BookingFacade bookingFacade, ConversationRepository conversationRepository,
                              MessageRepository messageRepository) {
        this.bookingFacade = bookingFacade;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public MessageResponse sendMessage(String bookingId, String body) {
        String userId = requireUser();
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);
        assertParticipant(userId, participants);
        String originalBody = validateBody(body);
        Instant now = Instant.now();
        Conversation conversation = findOrCreateConversation(bookingId, participants, now);

        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setSenderId(userId);
        message.setBody(originalBody);
        message.setStatus(MessageStatus.VISIBLE);
        message.setFlagged(false);
        message.setCreatedAt(now);
        Message saved = messageRepository.save(message);

        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        try {
            conversationRepository.save(conversation);
        } catch (DataAccessException e) {
            // The message is already stored; metadata failure must not make clients retry and duplicate it.
            log.warn("Conversation metadata update failed after message save");
        }
        return toResponse(saved, participants.status());
    }

    @Override
    public MessagePageResponse listMessages(String bookingId, int page, int size) {
        String userId = requireUser();
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);
        assertParticipant(userId, participants);
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Page must not be negative");
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Optional<Conversation> conversation = conversationRepository.findByChatKey(chatKey(bookingId, participants));
        if (conversation.isEmpty()) {
            return new MessagePageResponse(java.util.List.of(), page, pageSize, 0, 0);
        }
        Page<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.get().getId(), PageRequest.of(page, pageSize));
        return new MessagePageResponse(
                messages.map(message -> toResponse(message, participants.status())).getContent(),
                messages.getNumber(), messages.getSize(), messages.getTotalElements(), messages.getTotalPages());
    }

    private Conversation findOrCreateConversation(String bookingId, BookingParticipants participants, Instant now) {
        String chatKey = chatKey(bookingId, participants);
        Optional<Conversation> existing = conversationRepository.findByChatKey(chatKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Conversation created = new Conversation();
        created.setChatKey(chatKey);
        created.setBookingId(bookingId);
        created.setSeriesId(participants.seriesId());
        created.setListingId(participants.listingId());
        created.setCustomerId(participants.customerId());
        created.setProviderId(participants.providerId());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            return conversationRepository.save(created);
        } catch (DuplicateKeyException e) {
            return conversationRepository.findByChatKey(chatKey)
                    .orElseThrow(() -> new IllegalStateException("Conversation was duplicated but cannot be re-read", e));
        }
    }

    private String requireUser() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return userId;
    }

    private void assertParticipant(String userId, BookingParticipants participants) {
        if (!userId.equals(participants.customerId()) && !userId.equals(participants.providerId())) {
            throw new AccessDeniedException("Access denied: user is not a participant of this booking");
        }
    }

    private String validateBody(String body) {
        String trimmed = body == null ? null : body.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_EMPTY", "Message body cannot be blank");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_TOO_LONG",
                    "Message body cannot exceed 2000 characters");
        }
        return trimmed;
    }

    private String chatKey(String bookingId, BookingParticipants participants) {
        return participants.seriesId() != null ? participants.seriesId() : bookingId;
    }

    private MessageResponse toResponse(Message message, BookingStatus currentStatus) {
        String body = message.getStatus() == MessageStatus.HIDDEN ? null : message.getBody();
        // TODO: switch to BookingStatus.isContactVisible() once WP05 adds it.
        if (body != null && !CONTACT_VISIBLE_STATUSES.contains(currentStatus)) {
            body = ContactMasker.mask(body);
        }
        return new MessageResponse(message.getId(), message.getConversationId(), message.getSenderId(),
                body, message.getStatus(), message.getCreatedAt());
    }
}
