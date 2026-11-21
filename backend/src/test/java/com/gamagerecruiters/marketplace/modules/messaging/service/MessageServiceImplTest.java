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
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;

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

    private MessageServiceImpl service;
    private BookingParticipants participants;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(bookingFacade, conversationRepository, messageRepository);
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
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
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
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
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
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
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
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
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
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
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
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtDesc(
                anyString(), any(Pageable.class));
    }

    @Test
    void listLimitsPageSizeTo50() {
        Conversation conversation = conversation("conversation-1", BOOKING_ID);
        when(conversationRepository.findByChatKey(BOOKING_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMessages(BOOKING_ID, 0, 100);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationIdOrderByCreatedAtDesc(eq("conversation-1"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void negativePageIsRejected() {
        assertThatThrownBy(() -> service.listMessages(BOOKING_ID, -1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("INVALID_PAGINATION"));
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
}
