package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.bson.types.ObjectId;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;
import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ConversationPageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ConversationResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ModerationMessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ModerationMessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePollingResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationEvent;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationService;

@Service
public class MessageServiceImpl implements MessageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int POLLING_PAGE_SIZE = 100;
    private static final int MAX_BODY_LENGTH = 2000;
    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);

    private final BookingFacade bookingFacade;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AdminAccessPolicyService adminAccessPolicyService;
    private final MongoTemplate mongoTemplate;
    private final NotificationService notificationService;

    public MessageServiceImpl(BookingFacade bookingFacade, ConversationRepository conversationRepository,
                              MessageRepository messageRepository,
                              AdminAccessPolicyService adminAccessPolicyService,
                              MongoTemplate mongoTemplate,
                              NotificationService notificationService) {
        this.bookingFacade = bookingFacade;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.adminAccessPolicyService = adminAccessPolicyService;
        this.mongoTemplate = mongoTemplate;
        this.notificationService = notificationService;
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
        notifyOtherParticipant(saved, participants, bookingId, userId);

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
    public ConversationPageResponse listConversations(int page, int size) {
        int validPage = validPage(page);
        int pageSize = normalizedPageSize(size);
        String userId = requireUser();
        Page<Conversation> conversations = conversationRepository
                .findByCustomerIdOrProviderId(userId, userId,
                        conversationPageRequest(validPage, pageSize));
        List<ConversationResponse> content = conversations.getContent().stream()
                .map(conversation -> new ConversationResponse(
                        conversation.getId(), conversation.getBookingId(), conversation.getCustomerId(),
                        conversation.getProviderId(), conversation.getLastMessageAt(), conversation.getUpdatedAt()))
                .toList();
        return new ConversationPageResponse(content, conversations.getNumber(), conversations.getSize(),
                conversations.getTotalElements(), conversations.getTotalPages());
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
        Page<Message> messages = messageRepository.findByConversationId(
                conversation.get().getId(), historyPageRequest(page, pageSize));
        return new MessagePageResponse(
                messages.map(message -> toResponse(message, participants.status())).getContent(),
                messages.getNumber(), messages.getSize(), messages.getTotalElements(), messages.getTotalPages());
    }

    @Override
    public MessagePollingResponse listMessagesSince(String bookingId, Instant since, String afterId) {
        if (since == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SINCE_REQUIRED", "The since parameter is required");
        }
        String userId = requireUser();
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);
        assertParticipant(userId, participants);
        Optional<Conversation> conversation = conversationRepository.findByChatKey(chatKey(bookingId, participants));
        if (conversation.isEmpty()) {
            return new MessagePollingResponse(List.of(), POLLING_PAGE_SIZE, 0, 0, false, null, null);
        }
        PageRequest pageRequest = PageRequest.of(0, POLLING_PAGE_SIZE,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Object cursorId = afterId == null || afterId.isBlank()
                ? null : ObjectId.isValid(afterId) ? new ObjectId(afterId) : afterId;
        Page<Message> messages = cursorId == null
                ? messageRepository.findByConversationIdAndCreatedAtAfter(
                        conversation.get().getId(), since, pageRequest)
                : messageRepository.findAfterCursor(conversation.get().getId(), since, cursorId, pageRequest);
        List<MessageResponse> responses = messages.getContent().stream()
                .map(message -> toResponse(message, participants.status()))
                .toList();
        Message lastMessage = messages.getContent().isEmpty()
                ? null : messages.getContent().get(messages.getNumberOfElements() - 1);
        return new MessagePollingResponse(responses, messages.getSize(), messages.getTotalElements(),
                messages.getTotalPages(), messages.hasNext(),
                lastMessage == null ? null : lastMessage.getCreatedAt(),
                lastMessage == null ? null : lastMessage.getId());
    }

    @Override
    public MessagePageResponse getChatHistory(String bookingId, int page, int size) {
        int validPage = validPage(page);
        int pageSize = normalizedPageSize(size);
        String userId = requireUser();
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean party = isParticipant(participants, userId);
        boolean adminView = adminAccessPolicyService.canViewArea(authentication,
                com.gamagerecruiters.marketplace.modules.auth.domain.Role.ADMIN);
        if (!party && !adminView) {
            throw new AccessDeniedException("Access denied");
        }
        Optional<Conversation> conversation = conversationRepository.findByChatKey(chatKey(bookingId, participants));
        if (conversation.isEmpty()) {
            return new MessagePageResponse(java.util.List.of(), validPage, pageSize, 0, 0);
        }
        Page<Message> messages = messageRepository.findByConversationId(
                conversation.get().getId(), historyPageRequest(validPage, pageSize));
        return new MessagePageResponse(
                messages.map(message -> toResponse(message, participants.status())).getContent(),
                messages.getNumber(), messages.getSize(), messages.getTotalElements(), messages.getTotalPages());
    }

    @Override
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'ADMIN')")
    public ModerationMessagePageResponse getChatHistoryForMediator(String bookingId, int page, int size) {
        int validPage = validPage(page);
        int pageSize = normalizedPageSize(size);
        BookingParticipants participants = bookingFacade.getBookingParticipants(bookingId);
        Optional<Conversation> conversation = conversationRepository.findByChatKey(chatKey(bookingId, participants));
        if (conversation.isEmpty()) {
            return new ModerationMessagePageResponse(java.util.List.of(), validPage, pageSize, 0, 0);
        }
        Page<Message> messages = messageRepository.findByConversationId(
                conversation.get().getId(), historyPageRequest(validPage, pageSize));
        return new ModerationMessagePageResponse(
                messages.getContent().stream()
                        .map(message -> toModerationResponse(message, conversation.get()))
                        .toList(),
                messages.getNumber(), messages.getSize(), messages.getTotalElements(), messages.getTotalPages());
    }

    @Override
    public boolean isParticipant(String conversationId, String userId) {
        Optional<Conversation> conversation = conversationRepository.findById(conversationId);
        return conversation.isPresent()
                && userId != null
                && (userId.equals(conversation.get().getCustomerId())
                || userId.equals(conversation.get().getProviderId()));
    }

    @Override
    public boolean isMessageParticipant(String messageId, String userId) {
        Optional<Message> message = messageRepository.findById(messageId);
        if (message.isEmpty()) {
            return false;
        }
        return isParticipant(message.get().getConversationId(), userId);
    }

    @Override
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'MODERATOR')")
    public ModerationMessageResponse getMessageForModeration(String messageId) {
        Message message = findMessage(messageId);
        Conversation conversation = conversationRepository.findById(message.getConversationId()).orElse(null);
        return toModerationResponse(message, conversation);
    }

    @Override
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'MODERATOR')")
    public ModerationMessagePageResponse listFlaggedMessages(FlagSource flagSource, MessageStatus status,
                                                              int page, int size) {
        int validPage = validPage(page);
        int pageSize = normalizedPageSize(size);
        PageRequest pageable = PageRequest.of(validPage, pageSize,
                Sort.by(Sort.Order.desc("flaggedAt"), Sort.Order.desc("id")));

        Query query = Query.query(Criteria.where("flagged").is(true));
        if (flagSource != null) {
            query.addCriteria(Criteria.where("flagSource").is(flagSource));
        }
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status));
        }
        query.with(pageable);
        List<Message> messages = mongoTemplate.find(query, Message.class);
        Page<Message> flaggedMessages = PageableExecutionUtils.getPage(messages, pageable,
                () -> mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Message.class));

        Set<String> conversationIds = messages.stream()
                .map(Message::getConversationId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Conversation> conversations = new HashMap<>();
        if (!conversationIds.isEmpty()) {
            conversationRepository.findAllById(conversationIds)
                    .forEach(conversation -> conversations.put(conversation.getId(), conversation));
        }
        return new ModerationMessagePageResponse(messages.stream()
                .map(message -> toModerationResponse(message, conversations.get(message.getConversationId())))
                .toList(), flaggedMessages.getNumber(), flaggedMessages.getSize(),
                flaggedMessages.getTotalElements(), flaggedMessages.getTotalPages());
    }

    /**
     * WP09 calls this from the report endpoint so the participant check cannot be forgotten.
     */
    @Override
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public void reportMessage(String messageId) {
        String userId = requireUser();
        Message message = findMessage(messageId);
        Conversation conversation = conversationRepository.findById(message.getConversationId())
                .orElseThrow(() -> new ResourceNotFoundException("CONVERSATION_NOT_FOUND", "Conversation not found"));
        if (!userId.equals(conversation.getCustomerId()) && !userId.equals(conversation.getProviderId())) {
            throw new AccessDeniedException("Access denied: user is not a participant in this conversation");
        }
        flagMessage(messageId, FlagSource.USER_REPORT);
    }

    @Override
    public void flagMessage(String messageId, FlagSource source) {
        Message message = findMessageForUpdate(messageId);
        if (message.isFlagged()) {
            return;
        }
        message.setFlagged(true);
        message.setFlagSource(source);
        message.setFlaggedAt(Instant.now());
        try {
            messageRepository.save(message);
        } catch (OptimisticLockingFailureException e) {
            throw messageUpdateConflict();
        }
    }

    @Override
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'MODERATOR')")
    public ModerationMessageResponse hideMessage(String messageId, String moderatorId, String reason) {
        String authenticatedUserId = requireUser();
        if (!authenticatedUserId.equals(moderatorId)) {
            throw new AccessDeniedException("Moderator identity does not match authenticated user");
        }
        Message message = findMessageForUpdate(messageId);
        if (message.getStatus() == MessageStatus.HIDDEN) {
            Conversation conversation = conversationRepository.findById(message.getConversationId()).orElse(null);
            return toModerationResponse(message, conversation);
        }
        String validatedReason = validateHideReason(reason);
        message.setStatus(MessageStatus.HIDDEN);
        message.setHideReason(validatedReason);
        message.setHiddenBy(moderatorId);
        message.setHiddenAt(Instant.now());
        try {
            Message saved = messageRepository.save(message);
            Conversation conversation = conversationRepository.findById(saved.getConversationId()).orElse(null);
            return toModerationResponse(saved, conversation);
        } catch (OptimisticLockingFailureException e) {
            throw messageUpdateConflict();
        }
    }

    @Override
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'MODERATOR')")
    public ModerationMessageResponse clearFlag(String messageId) {
        Message message = findMessageForUpdate(messageId);
        message.setFlagged(false);
        message.setFlagSource(null);
        message.setFlaggedAt(null);
        try {
            Message saved = messageRepository.save(message);
            Conversation conversation = conversationRepository.findById(saved.getConversationId()).orElse(null);
            return toModerationResponse(saved, conversation);
        } catch (OptimisticLockingFailureException e) {
            throw messageUpdateConflict();
        }
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
        if (!isParticipant(participants, userId)) {
            throw new AccessDeniedException("Access denied: user is not a participant of this booking");
        }
    }

    private boolean isParticipant(BookingParticipants participants, String userId) {
        return userId.equals(participants.customerId()) || userId.equals(participants.providerId());
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

    private Message findMessage(String messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("MESSAGE_NOT_FOUND", "Message not found"));
    }

    /**
     * Initializes the version of a pre-optimistic-locking message before a moderation write.
     * The conditional update is safe under concurrent requests; normal optimistic locking applies
     * after the document is reloaded.
     */
    private Message findMessageForUpdate(String messageId) {
        Message message = findMessage(messageId);
        if (message.getVersion() != null) {
            return message;
        }
        Query legacyMessage = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(messageId),
                new Criteria().orOperator(Criteria.where("version").exists(false), Criteria.where("version").is(null))));
        mongoTemplate.updateFirst(legacyMessage, new Update().set("version", 0L), Message.class);
        return findMessage(messageId);
    }

    private String validateHideReason(String reason) {
        String trimmed = reason == null ? null : reason.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HIDE_REASON_REQUIRED",
                    "A reason is required when hiding a message");
        }
        if (trimmed.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HIDE_REASON_TOO_LONG",
                    "Hide reason cannot exceed 500 characters");
        }
        return trimmed;
    }

    private int validPage(int page) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Page must not be negative");
        }
        return page;
    }

    private int normalizedPageSize(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private PageRequest historyPageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private PageRequest conversationPageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
    }

    private ApiException messageUpdateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "MESSAGE_UPDATE_CONFLICT",
                "The message changed before this update");
    }

    private MessageResponse toResponse(Message message, BookingStatus currentStatus) {
        String body = message.getStatus() == MessageStatus.HIDDEN ? null : message.getBody();
        if (body != null && (currentStatus == null || !currentStatus.isContactVisible())) {
            body = ContactMasker.mask(body);
        }
        return new MessageResponse(message.getId(), message.getConversationId(), message.getSenderId(),
                body, message.getStatus(), message.getCreatedAt());
    }

    private void notifyOtherParticipant(Message message, BookingParticipants participants, String bookingId, String senderId) {
        if (message == null || message.getStatus() == MessageStatus.HIDDEN) return;
        String recipient = senderId.equals(participants.customerId()) ? participants.providerId() : participants.customerId();
        if (recipient == null || recipient.equals(senderId)) return;
        try { notificationService.notify(new NotificationEvent(NotificationType.NEW_MESSAGE, recipient, bookingId, Map.of("conversationId", message.getConversationId()))); }
        catch (Exception ignored) { }
    }

    private ModerationMessageResponse toModerationResponse(Message message, Conversation conversation) {
        return new ModerationMessageResponse(
                message.getId(), message.getConversationId(),
                conversation == null ? null : conversation.getBookingId(),
                conversation == null ? null : conversation.getListingId(),
                message.getSenderId(), message.getBody(), message.getStatus(), message.isFlagged(),
                message.getFlagSource(), message.getHideReason(), message.getHiddenBy(),
                message.getHiddenAt(), message.getCreatedAt());
    }
}
