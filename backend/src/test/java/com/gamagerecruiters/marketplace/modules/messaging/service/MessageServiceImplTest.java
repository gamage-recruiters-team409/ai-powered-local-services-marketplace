package com.gamagerecruiters.marketplace.modules.messaging.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;
import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePollingResponse;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceImplTest {

    private static final String BOOKING_ID = "b-1";
    private static final String SERIES_ID = "series-1";
    private static final String CUSTOMER_ID = "c-1";
    private static final String PROVIDER_ID = "p-1";
    private static final Instant CREATED_AT = Instant.parse("2026-11-17T05:00:00Z");

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AdminAccessPolicyService adminAccessPolicyService;

    @Mock
    private MongoTemplate mongoTemplate;

    private MessageServiceImpl service;
    private BookingParticipants participants;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(
                bookingFacade, conversationRepository, messageRepository, adminAccessPolicyService, mongoTemplate);
        participants = participants(BookingStatus.PENDING_PROVIDER, null);
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenAnswer(invocation -> participants);
        when(conversationRepository.findByChatKey(anyString())).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            conversation.setId("conversation-1");
            return conversation;
        });
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-1");
            return message;
        });
        loginAs(CUSTOMER_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstMessageCreatesConversationAndStoresOriginalBody() {
        MessageResponse response = service.sendMessage(BOOKING_ID, "  Meet me at 0771234567  ");

        assertThat(response.body()).isEqualTo("Meet me at [contact hidden]");
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("Meet me at 0771234567");
        verify(conversationRepository, org.mockito.Mockito.times(2)).save(any(Conversation.class));
    }

    @Test
    void secondMessageReusesExistingConversation() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));

        service.sendMessage(BOOKING_ID, "second");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo("conversation-1");
        verify(conversationRepository, org.mockito.Mockito.times(1)).findByChatKey(BOOKING_ID);
    }

    @Test
    void seriesIdIsUsedAsChatKeyWhenPresent() {
        participants = participants(BookingStatus.CONFIRMED, SERIES_ID);

        service.sendMessage(BOOKING_ID, "hello");

        verify(conversationRepository).findByChatKey(SERIES_ID);
    }

    @Test
    void bookingIdIsUsedAsChatKeyWhenNoSeries() {
        service.sendMessage(BOOKING_ID, "hello");

        verify(conversationRepository).findByChatKey(BOOKING_ID);
    }

    @Test
    void nonParticipantGetsAccessDenied() {
        loginAs("stranger");

        assertThatThrownBy(() -> service.sendMessage(BOOKING_ID, "hello"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listMessages(BOOKING_ID, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void unauthenticatedUserGetsUnauthorized() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.sendMessage(BOOKING_ID, "hello"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertUnauthorized((ApiException) error));
        assertThatThrownBy(() -> service.listMessages(BOOKING_ID, 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertUnauthorized((ApiException) error));
    }

    @Test
    void blankBodyIsRejected() {
        assertThatThrownBy(() -> service.sendMessage(BOOKING_ID, "  "))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("MESSAGE_EMPTY"));
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void tooLongBodyIsRejected() {
        assertThatThrownBy(() -> service.sendMessage(BOOKING_ID, "x".repeat(2001)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("MESSAGE_TOO_LONG"));
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void bodyIsTrimmedBeforeSaving() {
        service.sendMessage(BOOKING_ID, "  original text  ");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("original text");
    }

    @Test
    void contactsAreMaskedWhilePendingProvider() {
        participants = participants(BookingStatus.PENDING_PROVIDER, null);

        assertThat(service.sendMessage(BOOKING_ID, "Email a.person@example.com").body())
                .isEqualTo("Email [contact hidden]");
    }

    @Test
    void contactsAreMaskedWhileAwaitingPayment() {
        participants = participants(BookingStatus.AWAITING_PAYMENT, null);

        assertThat(service.sendMessage(BOOKING_ID, "Call 0771234567").body())
                .isEqualTo("Call [contact hidden]");
    }

    @Test
    void sendMessageMasksPhoneAfterIsoDateWhileBookingIsUnconfirmed() {
        participants = participants(BookingStatus.PENDING_PROVIDER, null);

        MessageResponse response = service.sendMessage(BOOKING_ID, "2026-11-20 0771234567");

        assertThat(response.body()).isEqualTo("2026-11-20 [contact hidden]");
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("2026-11-20 0771234567");
    }

    @Test
    void listMessagesMasksPhoneWithDoubleSpacesWhileBookingIsUnconfirmed() {
        participants = participants(BookingStatus.PENDING_PROVIDER, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "077  123  4567", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        MessageResponse response = service.listMessages(BOOKING_ID, 0, 20).content().get(0);

        assertThat(response.body()).isEqualTo("[contact hidden]");
    }

    @Test
    void listMessagesShowsSamePhoneAfterBookingIsConfirmed() {
        participants = participants(BookingStatus.CONFIRMED, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "077  123  4567", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        MessageResponse response = service.listMessages(BOOKING_ID, 0, 20).content().get(0);

        assertThat(response.body()).isEqualTo("077  123  4567");
    }

    @Test
    void contactsAreVisibleWhenConfirmed() {
        participants = participants(BookingStatus.CONFIRMED, null);

        assertThat(service.sendMessage(BOOKING_ID, "Call 0771234567").body())
                .isEqualTo("Call 0771234567");
    }

    @Test
    void contactsAreVisibleWhenInProgress() {
        participants = participants(BookingStatus.IN_PROGRESS, null);

        assertThat(service.sendMessage(BOOKING_ID, "Call 0771234567").body())
                .isEqualTo("Call 0771234567");
    }

    @Test
    void sameStoredMessageBecomesVisibleAfterBookingIsConfirmed() {
        participants = participants(BookingStatus.AWAITING_PAYMENT, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "Call 0771234567", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        MessageResponse masked = service.listMessages(BOOKING_ID, 0, 20).content().get(0);
        participants = participants(BookingStatus.CONFIRMED, null);
        MessageResponse visible = service.listMessages(BOOKING_ID, 0, 20).content().get(0);

        assertThat(masked.body()).isEqualTo("Call [contact hidden]");
        assertThat(visible.body()).isEqualTo("Call 0771234567");
        assertThat(message.getBody()).isEqualTo("Call 0771234567");
    }

    @Test
    void hiddenMessageHasNoBody() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "secret", MessageStatus.HIDDEN);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        assertThat(service.listMessages(BOOKING_ID, 0, 20).content().get(0).body()).isNull();
    }

    @Test
    void bookingNotFoundPropagates() {
        ResourceNotFoundException expected = new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found");
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenThrow(expected);

        assertThatThrownBy(() -> service.sendMessage(BOOKING_ID, "hello")).isSameAs(expected);
        assertThatThrownBy(() -> service.listMessages(BOOKING_ID, 0, 20)).isSameAs(expected);
    }

    @Test
    void duplicateKeyOnFirstMessageRereadsExistingConversation() {
        Conversation existing = conversation("conversation-1", BOOKING_ID);
        when(conversationRepository.findByChatKey(BOOKING_ID))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(conversationRepository.save(any(Conversation.class)))
                .thenThrow(new DuplicateKeyException("duplicate chat key"))
                .thenReturn(existing);

        MessageResponse response = service.sendMessage(BOOKING_ID, "hello");

        assertThat(response.conversationId()).isEqualTo("conversation-1");
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    void optimisticLockOnConversationUpdateStillReturnsSavedMessage() {
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new OptimisticLockingFailureException("version mismatch");
            }
            Conversation conversation = invocation.getArgument(0);
            conversation.setId("conversation-1");
            return conversation;
        }).when(conversationRepository).save(any(Conversation.class));

        MessageResponse response = service.sendMessage(BOOKING_ID, "hello");

        assertThat(response.body()).isEqualTo("hello");
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    void metadataUpdateFailureWithGenericDataAccessExceptionStillReturnsSavedMessage() {
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("metadata unavailable");
            }
            Conversation conversation = invocation.getArgument(0);
            conversation.setId("conversation-1");
            return conversation;
        }).when(conversationRepository).save(any(Conversation.class));

        MessageResponse response = service.sendMessage(BOOKING_ID, "hello");

        assertThat(response.body()).isEqualTo("hello");
        verify(messageRepository, org.mockito.Mockito.times(1)).save(any(Message.class));
    }

    @Test
    void listReturnsMessagesAsResponses() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        var result = service.listMessages(BOOKING_ID, 0, 20);

        assertThat(result.content()).containsExactly(
                new MessageResponse("message-1", "conversation-1", null, "hello", MessageStatus.VISIBLE, CREATED_AT));
    }

    @Test
    void listReturnsEmptyPageWhenNoConversation() {
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.empty());

        var result = service.listMessages(BOOKING_ID, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(messageRepository, never()).findByConversationId(
                anyString(), any(Pageable.class));
    }

    @Test
    void listLimitsPageSizeTo50() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMessages(BOOKING_ID, 0, 100);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationId(eq("conversation-1"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void negativePageIsRejected() {
        assertThatThrownBy(() -> service.listMessages(BOOKING_ID, -1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("INVALID_PAGINATION"));
    }

    @Test
    void participantCheckUsesBookingParticipants() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        conversation.setCustomerId(CUSTOMER_ID);
        conversation.setProviderId(PROVIDER_ID);
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));

        assertThat(service.isParticipant("conversation-1", CUSTOMER_ID)).isTrue();
        assertThat(service.isParticipant("conversation-1", PROVIDER_ID)).isTrue();
        assertThat(service.isParticipant("conversation-1", "stranger")).isFalse();
        assertThat(service.isParticipant("missing", CUSTOMER_ID)).isFalse();
    }

    @Test
    void mediatorHistoryReturnsHiddenBodyForModeration() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message hidden = message("message-1", "conversation-1", "unsafe content", MessageStatus.HIDDEN);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hidden)));

        var response = service.getChatHistoryForMediator(BOOKING_ID, 0, 20).content().get(0);

        assertThat(response.body()).isEqualTo("unsafe content");
        assertThat(response.status()).isEqualTo(MessageStatus.HIDDEN);
    }

    @Test
    void flagMessagePersistsFlag() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);

        service.flagMessage("message-1", FlagSource.USER_REPORT);
        service.flagMessage("message-1", FlagSource.AI);

        assertThat(message.isFlagged()).isTrue();
        assertThat(message.getFlagSource()).isEqualTo(FlagSource.USER_REPORT);
        assertThat(message.getFlaggedAt()).isNotNull();
        verify(messageRepository).save(message);
    }

    @Test
    void hideMessageRequiresReasonAndStoresItWithoutDeletingMessage() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        loginAs(CUSTOMER_ID);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);

        var response = service.hideMessage("message-1", CUSTOMER_ID, "  policy violation  ");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.HIDDEN);
        assertThat(message.getHideReason()).isEqualTo("policy violation");
        assertThat(message.getHiddenBy()).isEqualTo(CUSTOMER_ID);
        assertThat(message.getHiddenAt()).isNotNull();
        assertThat(response.body()).isEqualTo("hello");
        verify(messageRepository, never()).delete(any(Message.class));
        verify(messageRepository).save(message);
    }

    @Test
    void hideMessageRejectsBlankReason() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        loginAs(CUSTOMER_ID);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.hideMessage("message-1", CUSTOMER_ID, "  "))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo("HIDE_REASON_REQUIRED"));
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void clearFlagPersistsUnflaggedMessage() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        message.setFlagged(true);
        message.setFlagSource(FlagSource.AI);
        message.setFlaggedAt(CREATED_AT);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);

        service.clearFlag("message-1");

        assertThat(message.isFlagged()).isFalse();
        assertThat(message.getFlagSource()).isNull();
        assertThat(message.getFlaggedAt()).isNull();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.VISIBLE);
        verify(messageRepository).save(message);
    }

    @Test
    void isMessageParticipantReturnsTrueForCustomerAndProvider() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        conversation.setCustomerId(CUSTOMER_ID);
        conversation.setProviderId(PROVIDER_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));

        assertThat(service.isMessageParticipant("message-1", CUSTOMER_ID)).isTrue();
        assertThat(service.isMessageParticipant("message-1", PROVIDER_ID)).isTrue();
        assertThat(service.isMessageParticipant("message-1", "stranger")).isFalse();
    }

    @Test
    void isMessageParticipantReturnsFalseForMissingMessageOrConversation() {
        when(messageRepository.findById("missing")).thenReturn(Optional.empty());
        Message message = message("message-1", "missing-conversation", "hello", MessageStatus.VISIBLE);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(conversationRepository.findById("missing-conversation")).thenReturn(Optional.empty());

        assertThat(service.isMessageParticipant("missing", CUSTOMER_ID)).isFalse();
        assertThat(service.isMessageParticipant("message-1", CUSTOMER_ID)).isFalse();
    }

    @Test
    void hideMessageRejectsModeratorIdentityMismatch() {
        loginAs(CUSTOMER_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.hideMessage("message-1", "other-moderator", "Policy violation"))
                .isInstanceOf(AccessDeniedException.class);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void hideMessageKeepsFirstReasonWhenAlreadyHidden() {
        loginAs(CUSTOMER_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.HIDDEN);
        message.setHideReason("First reason");
        message.setHiddenBy("first-moderator");
        message.setHiddenAt(CREATED_AT);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));

        var response = service.hideMessage("message-1", CUSTOMER_ID, "Second reason");

        assertThat(response.hideReason()).isEqualTo("First reason");
        assertThat(response.hiddenBy()).isEqualTo("first-moderator");
        assertThat(response.hiddenAt()).isEqualTo(CREATED_AT);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void hideMessageRejectsReasonOver500Characters() {
        loginAs(CUSTOMER_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.hideMessage("message-1", CUSTOMER_ID, "x".repeat(501)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo("HIDE_REASON_TOO_LONG"));
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void clearFlagDoesNotUnhideMessage() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.HIDDEN);
        message.setFlagged(true);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenReturn(message);

        service.clearFlag("message-1");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.HIDDEN);
        assertThat(message.isFlagged()).isFalse();
    }

    @Test
    void getChatHistoryParticipantOkAndHiddenBodyIsNull() {
        participants = participants(BookingStatus.CONFIRMED, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message hidden = message("message-1", "conversation-1", "secret", MessageStatus.HIDDEN);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hidden)));

        var response = service.getChatHistory(BOOKING_ID, 0, 20).content().get(0);

        assertThat(response.body()).isNull();
    }

    @Test
    void getChatHistoryStrangerIsDenied() {
        loginAs("stranger");

        assertThatThrownBy(() -> service.getChatHistory(BOOKING_ID, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getChatHistoryForMediatorReturnsEmptyPageWithoutConversation() {
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.empty());

        var response = service.getChatHistoryForMediator(BOOKING_ID, 0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void getChatHistoryMasksPhoneUntilBookingIsConfirmed() {
        participants = participants(BookingStatus.PENDING_PROVIDER, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "Call 0771234567", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        var masked = service.getChatHistory(BOOKING_ID, 0, 20).content().get(0);
        participants = participants(BookingStatus.CONFIRMED, null);
        var visible = service.getChatHistory(BOOKING_ID, 0, 20).content().get(0);

        assertThat(masked.body()).isEqualTo("Call [contact hidden]");
        assertThat(visible.body()).isEqualTo("Call 0771234567");
    }

    @Test
    void superAdminChatHistoryGetsMaskedPhoneText() {
        loginAs("super-admin");
        UserPrincipal superAdmin = new UserPrincipal("super-admin", "super@test.local", Role.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(superAdmin, null, superAdmin.getAuthorities()));
        when(adminAccessPolicyService.canViewArea(any(), eq(Role.ADMIN))).thenReturn(true);
        participants = participants(BookingStatus.PENDING_PROVIDER, null);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message message = message("message-1", "conversation-1", "Call 0771234567", MessageStatus.VISIBLE);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(message)));

        var response = service.getChatHistory(BOOKING_ID, 0, 20).content().get(0);

        assertThat(response.body()).isEqualTo("Call [contact hidden]");
    }

    @Test
    void unauthenticatedGetChatHistoryIsRejectedBeforeBookingLookup() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.getChatHistory(BOOKING_ID, 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("UNAUTHORIZED"));
        verify(bookingFacade, never()).getBookingParticipants(anyString());
    }

    @Test
    void listMessagesSinceUsesOneHundredItemPageAndDeterministicSort() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdAndCreatedAtAfter(
                anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMessagesSince(BOOKING_ID, Instant.parse("2026-11-19T10:00:00Z"), null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationIdAndCreatedAtAfter(
                eq("conversation-1"), eq(Instant.parse("2026-11-19T10:00:00Z")), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getSort().toString()).isEqualTo("createdAt: ASC,id: ASC");
    }

    @Test
    void pollingResponseReportsContinuationWhenMoreMessagesExist() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Instant cursor = Instant.parse("2026-11-19T10:00:00Z");
        Message last = message("message-100", "conversation-1", "last", MessageStatus.VISIBLE);
        last.setCreatedAt(cursor.plusSeconds(1));
        List<Message> firstHundred = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(index -> {
                    Message message = message("message-" + index, "conversation-1", "body", MessageStatus.VISIBLE);
                    message.setCreatedAt(index == 100 ? last.getCreatedAt() : cursor.plusMillis(index));
                    return message;
                }).toList();
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdAndCreatedAtAfter(anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(firstHundred, Pageable.ofSize(100), 101));

        MessagePollingResponse response = service.listMessagesSince(BOOKING_ID, cursor, null);

        assertThat(response.content()).hasSize(100);
        assertThat(response.totalElements()).isEqualTo(101);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextSince()).isEqualTo(last.getCreatedAt());
        assertThat(response.nextMessageId()).isEqualTo("message-100");
    }

    @Test
    void pollingCursorReturnsMessagesWithMatchingTimestamp() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Instant cursor = Instant.parse("2026-11-19T10:00:00Z");
        Message next = message("message-b", "conversation-1", "next", MessageStatus.VISIBLE);
        next.setCreatedAt(cursor);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findAfterCursor(anyString(), any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(next), Pageable.ofSize(100), 1));

        MessagePollingResponse response = service.listMessagesSince(BOOKING_ID, cursor, "message-a");

        assertThat(response.content()).extracting(MessageResponse::id).containsExactly("message-b");
        assertThat(response.nextSince()).isEqualTo(cursor);
        assertThat(response.nextMessageId()).isEqualTo("message-b");
        verify(messageRepository).findAfterCursor(eq("conversation-1"), eq(cursor), eq("message-a"), any(Pageable.class));
    }

    @Test
    void validObjectIdCursorReachesRepositoryAsObjectId() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Instant cursor = Instant.parse("2026-11-20T08:30:00Z");
        String afterId = "507f1f77bcf86cd799439011";
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findAfterCursor(anyString(), any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMessagesSince(BOOKING_ID, cursor, afterId);

        ArgumentCaptor<Object> cursorCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messageRepository).findAfterCursor(eq("conversation-1"), eq(cursor), cursorCaptor.capture(),
                any(Pageable.class));
        assertThat(cursorCaptor.getValue()).isInstanceOf(ObjectId.class);
        assertThat(cursorCaptor.getValue()).isEqualTo(new ObjectId(afterId));
    }

    @Test
    void legacyStringCursorReachesRepositoryAsString() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Instant cursor = Instant.parse("2026-11-20T08:30:00Z");
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findAfterCursor(anyString(), any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMessagesSince(BOOKING_ID, cursor, "legacy-message-a");

        ArgumentCaptor<Object> cursorCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messageRepository).findAfterCursor(eq("conversation-1"), eq(cursor), cursorCaptor.capture(),
                any(Pageable.class));
        assertThat(cursorCaptor.getValue()).isEqualTo("legacy-message-a");
    }

    @Test
    void emptyPollingResultHasNoContinuationCursor() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Instant cursor = Instant.parse("2026-11-20T08:30:00Z");
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdAndCreatedAtAfter(anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        MessagePollingResponse response = service.listMessagesSince(BOOKING_ID, cursor, null);

        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextSince()).isNull();
        assertThat(response.nextMessageId()).isNull();
    }

    @Test
    void versionedMessageDoesNotRequireLegacyVersionInitialization() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        message.setVersion(4L);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));

        service.flagMessage("message-1", FlagSource.USER_REPORT);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(Message.class));
        assertThat(message.getVersion()).isEqualTo(4L);
    }

    @Test
    void versionlessMessageIsInitializedOnceBeforeModerationUpdate() {
        Message legacy = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        Message initialized = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        initialized.setVersion(0L);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(legacy), Optional.of(initialized));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Message.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        service.flagMessage("message-1", FlagSource.USER_REPORT);

        verify(mongoTemplate, org.mockito.Mockito.times(1))
                .updateFirst(any(Query.class), any(Update.class), eq(Message.class));
        assertThat(initialized.getVersion()).isZero();
    }

    @Test
    void flagMessageThrowsConflictWhenSaveHasOptimisticLockFailure() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        message.setVersion(0L);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenThrow(new OptimisticLockingFailureException("lost update"));

        assertConflict(() -> service.flagMessage("message-1", FlagSource.USER_REPORT));
    }

    @Test
    void hideMessageThrowsConflictWhenSaveHasOptimisticLockFailure() {
        loginAs(CUSTOMER_ID);
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        message.setVersion(0L);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenThrow(new OptimisticLockingFailureException("lost update"));

        assertConflict(() -> service.hideMessage("message-1", CUSTOMER_ID, "Policy violation"));
    }

    @Test
    void clearFlagThrowsConflictWhenSaveHasOptimisticLockFailure() {
        Message message = message("message-1", "conversation-1", "hello", MessageStatus.VISIBLE);
        message.setVersion(0L);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(message)).thenThrow(new OptimisticLockingFailureException("lost update"));

        assertConflict(() -> service.clearFlag("message-1"));
    }

    @Test
    void mediatorHistoryUsesConversationLookupAtMostOnceForMultipleMessages() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        Message first = message("message-1", "conversation-1", "first", MessageStatus.VISIBLE);
        Message second = message("message-2", "conversation-1", "second", MessageStatus.HIDDEN);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second)));

        var response = service.getChatHistoryForMediator(BOOKING_ID, 0, 20);

        assertThat(response.content()).hasSize(2);
        verify(conversationRepository, never()).findById(anyString());
    }

    @Test
    void listFlaggedMessagesReturnsOriginalTextAndLoadsConversationsInOneBatch() {
        Message flagged = message("message-1", "conversation-1", "Original reported text", MessageStatus.VISIBLE);
        flagged.setFlagged(true);
        flagged.setFlagSource(FlagSource.USER_REPORT);
        flagged.setFlaggedAt(CREATED_AT);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        when(mongoTemplate.find(any(Query.class), eq(Message.class))).thenReturn(List.of(flagged));
        when(conversationRepository.findAllById(any())).thenReturn(List.of(conversation));

        var response = service.listFlaggedMessages(null, null, 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).body()).isEqualTo("Original reported text");
        verify(conversationRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(conversationRepository, never()).findById(anyString());
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(Message.class));
        assertThat(queryCaptor.getValue().getQueryObject()).containsEntry("flagged", true);
    }

    @Test
    void listFlaggedMessagesAppliesOptionalFlagSourceAndStatusFilters() {
        when(mongoTemplate.find(any(Query.class), eq(Message.class))).thenReturn(List.of());

        service.listFlaggedMessages(FlagSource.USER_REPORT, null, 0, 20);
        service.listFlaggedMessages(null, MessageStatus.HIDDEN, 0, 20);
        service.listFlaggedMessages(FlagSource.AI, MessageStatus.VISIBLE, 0, 20);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(3)).find(queryCaptor.capture(), eq(Message.class));
        assertThat(queryCaptor.getAllValues().get(0).getQueryObject())
                .containsEntry("flagged", true).containsEntry("flagSource", FlagSource.USER_REPORT);
        assertThat(queryCaptor.getAllValues().get(1).getQueryObject())
                .containsEntry("flagged", true).containsEntry("status", MessageStatus.HIDDEN);
        assertThat(queryCaptor.getAllValues().get(2).getQueryObject())
                .containsEntry("flagged", true).containsEntry("flagSource", FlagSource.AI)
                .containsEntry("status", MessageStatus.VISIBLE);
    }

    @Test
    void listFlaggedMessagesReturnsEmptyPageWithoutLoadingConversations() {
        when(mongoTemplate.find(any(Query.class), eq(Message.class))).thenReturn(List.of());

        var response = service.listFlaggedMessages(null, null, 0, 20);

        assertThat(response.content()).isEmpty();
        verify(conversationRepository, never()).findAllById(any());
    }

    @Test
    void listFlaggedMessagesPreservesNewestFlagOrderIncludingMatchingTimestamps() {
        Message newer = message("message-z", "conversation-1", "newer", MessageStatus.VISIBLE);
        newer.setFlagged(true);
        newer.setFlaggedAt(CREATED_AT);
        Message olderId = message("message-a", "conversation-1", "older id", MessageStatus.VISIBLE);
        olderId.setFlagged(true);
        olderId.setFlaggedAt(CREATED_AT);
        when(mongoTemplate.find(any(Query.class), eq(Message.class))).thenReturn(List.of(newer, olderId));
        when(conversationRepository.findAllById(any())).thenReturn(List.of(conversation("conversation-1", BOOKING_ID)));

        var response = service.listFlaggedMessages(null, null, 0, 20);

        assertThat(response.content()).extracting(item -> item.id())
                .containsExactly("message-z", "message-a");
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(Message.class));
        assertThat(queryCaptor.getValue().getSortObject()).containsEntry("flaggedAt", -1).containsEntry("id", -1);
    }

    @Test
    void listFlaggedMessagesCapsPageSizeAtFifty() {
        when(mongoTemplate.find(any(Query.class), eq(Message.class))).thenReturn(List.of());

        service.listFlaggedMessages(null, null, 1, 100);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(Message.class));
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(50);
        assertThat(queryCaptor.getValue().getSkip()).isEqualTo(50L);
    }

    @Test
    void listFlaggedMessagesRejectsNegativePage() {
        assertThatThrownBy(() -> service.listFlaggedMessages(null, null, -1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("INVALID_PAGINATION"));
        verify(mongoTemplate, never()).find(any(Query.class), eq(Message.class));
    }

    @Test
    void participantCanReportMessage() {
        Message message = message("message-1", "conversation-1", "reportable", MessageStatus.VISIBLE);
        message.setVersion(0L);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        conversation.setCustomerId(CUSTOMER_ID);
        conversation.setProviderId(PROVIDER_ID);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));

        service.reportMessage("message-1");

        assertThat(message.isFlagged()).isTrue();
        assertThat(message.getFlagSource()).isEqualTo(FlagSource.USER_REPORT);
        verify(messageRepository).save(message);
    }

    @Test
    void strangerCannotReportMessageAndNothingIsSaved() {
        loginAs("stranger");
        Message message = message("message-1", "conversation-1", "reportable", MessageStatus.VISIBLE);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        conversation.setCustomerId(CUSTOMER_ID);
        conversation.setProviderId(PROVIDER_ID);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service.reportMessage("message-1")).isInstanceOf(AccessDeniedException.class);
        assertThat(message.isFlagged()).isFalse();
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void reportingUnknownMessageReturnsNotFound() {
        when(messageRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportMessage("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(error -> assertThat(((ResourceNotFoundException) error).getErrorCode())
                        .isEqualTo("MESSAGE_NOT_FOUND"));
    }

    @Test
    void unauthenticatedUserCannotReportMessage() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.reportMessage("message-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertUnauthorized((ApiException) error));
    }

    @Test
    void reportingAnAlreadyFlaggedMessageKeepsFirstFlagDetails() {
        Instant firstFlaggedAt = CREATED_AT.minusSeconds(60);
        Message message = message("message-1", "conversation-1", "reportable", MessageStatus.VISIBLE);
        message.setVersion(0L);
        message.setFlagged(true);
        message.setFlagSource(FlagSource.AI);
        message.setFlaggedAt(firstFlaggedAt);
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        conversation.setCustomerId(CUSTOMER_ID);
        conversation.setProviderId(PROVIDER_ID);
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(conversation));

        service.reportMessage("message-1");

        assertThat(message.getFlagSource()).isEqualTo(FlagSource.AI);
        assertThat(message.getFlaggedAt()).isEqualTo(firstFlaggedAt);
        verify(messageRepository, never()).save(any(Message.class));
    }

    private static BookingParticipants participants(BookingStatus status, String seriesId) {
        return new BookingParticipants(BOOKING_ID, seriesId, "listing-1", CUSTOMER_ID, PROVIDER_ID,
                status, null, null);
    }

    private static Conversation conversation(String id, String bookingId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setBookingId(bookingId);
        conversation.setChatKey(bookingId);
        return conversation;
    }

    private static Message message(String id, String conversationId, String body, MessageStatus status) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setBody(body);
        message.setStatus(status);
        message.setCreatedAt(CREATED_AT);
        return message;
    }

    private static void loginAs(String userId) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void assertUnauthorized(ApiException exception) {
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exception.getErrorCode()).isEqualTo("UNAUTHORIZED");
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getErrorCode()).isEqualTo("MESSAGE_UPDATE_CONFLICT");
                });
    }
}
