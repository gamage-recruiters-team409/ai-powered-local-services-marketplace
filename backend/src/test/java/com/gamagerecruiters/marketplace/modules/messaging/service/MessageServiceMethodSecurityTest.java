package com.gamagerecruiters.marketplace.modules.messaging.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Message;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;
import com.gamagerecruiters.marketplace.modules.messaging.repository.ConversationRepository;
import com.gamagerecruiters.marketplace.modules.messaging.repository.MessageRepository;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationService;
import com.gamagerecruiters.marketplace.modules.messaging.domain.Conversation;
import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ModerationMessageResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.util.List;

@SpringJUnitConfig(classes = MessageServiceMethodSecurityTest.TestConfig.class)
class MessageServiceMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    @Import(AdminAccessPolicyService.class)
    static class TestConfig {
        @Bean
        MessageServiceImpl messageService(BookingFacade bookingFacade,
                                          ConversationRepository conversationRepository,
                                          MessageRepository messageRepository,
                                          AdminAccessPolicyService policy,
                                          MongoTemplate mongoTemplate) {
            return new MessageServiceImpl(bookingFacade, conversationRepository, messageRepository, policy, mongoTemplate,
                    org.mockito.Mockito.mock(NotificationService.class));
        }
    }

    // Use the interface so Spring applies method-security through the service proxy.
    @Autowired
    private MessageService service;

    @MockitoBean
    private BookingFacade bookingFacade;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private MessageRepository messageRepository;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Message message() {
        Message message = new Message();
        message.setId("message-1");
        message.setConversationId("conversation-1");
        message.setSenderId("sender-1");
        message.setBody("Original text");
        message.setStatus(MessageStatus.VISIBLE);
        message.setCreatedAt(Instant.parse("2026-11-19T10:00:00Z"));
        return message;
    }

    @Test
    void moderatorCanHideClearFlagAndReadModerationData() {
        loginAs("moderator-1", Role.MODERATOR);
        Message message = message();
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.getMessageForModeration("message-1");
        ModerationMessageResponse hidden = service.hideMessage(
                "message-1", "moderator-1", "Policy violation");
        assertThat(hidden.status()).isEqualTo(MessageStatus.HIDDEN);
        assertThat(hidden.body()).isEqualTo("Original text");
        assertThat(hidden.hiddenBy()).isEqualTo("moderator-1");
        assertThat(hidden.hideReason()).isEqualTo("Policy violation");

        message.setFlagged(true);
        message.setFlagSource(FlagSource.USER_REPORT);
        ModerationMessageResponse cleared = service.clearFlag("message-1");
        assertThat(cleared.status()).isEqualTo(MessageStatus.HIDDEN);
        assertThat(cleared.flagged()).isFalse();
    }

    @Test
    void superAdminCanReadModerationDataButCannotHideOrClearFlag() {
        loginAs("super-1", Role.SUPER_ADMIN);
        Message message = message();
        when(messageRepository.findById("message-1")).thenReturn(Optional.of(message));
        service.getMessageForModeration("message-1");

        assertThatThrownBy(() -> service.hideMessage("message-1", "super-1", "Reason"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.clearFlag("message-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moderatorAndSuperAdminCanListFlaggedMessages() {
        when(mongoTemplate.find(any(Query.class), org.mockito.ArgumentMatchers.eq(Message.class)))
                .thenReturn(List.of());

        for (Role role : new Role[]{Role.MODERATOR, Role.SUPER_ADMIN}) {
            loginAs("viewer-" + role.name(), role);
            service.listFlaggedMessages(null, null, 0, 20);
        }
    }

    @Test
    void customerProviderAndAdminCannotListFlaggedMessages() {
        for (Role role : new Role[]{Role.CUSTOMER, Role.PROVIDER, Role.ADMIN}) {
            loginAs("denied-" + role.name(), role);
            assertThatThrownBy(() -> service.listFlaggedMessages(null, null, 0, 20))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void moderatorAndGuestCannotReportMessages() {
        loginAs("moderator-1", Role.MODERATOR);
        assertThatThrownBy(() -> service.reportMessage("message-1"))
                .isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.reportMessage("message-1"))
                .isInstanceOfAny(AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void customerAndProviderCannotAccessModerationOperations() {
        for (Role role : new Role[]{Role.CUSTOMER, Role.PROVIDER}) {
            loginAs("party-1", role);
            assertThatThrownBy(() -> service.getMessageForModeration("message-1"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.hideMessage("message-1", "party-1", "Reason"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.clearFlag("message-1"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void disputeConsoleAdminCanCallMediatorHistory() {
        loginAs("admin-1", Role.ADMIN);
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants("booking-1", null, "listing-1",
                        "customer-1", "provider-1",
                        com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.CONFIRMED,
                        null, null));
        when(conversationRepository.findByChatKey("booking-1")).thenReturn(Optional.empty());

        service.getChatHistoryForMediator("booking-1", 0, 20);
    }

    @Test
    void superAdminAndOtherRolesCannotCallMediatorHistory() {
        for (Role role : new Role[]{Role.SUPER_ADMIN, Role.MODERATOR, Role.CUSTOMER, Role.PROVIDER}) {
            loginAs("user-1", role);
            assertThatThrownBy(() -> service.getChatHistoryForMediator("booking-1", 0, 20))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void participantCanReadChatHistory() {
        loginAs("customer-1", Role.CUSTOMER);
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants("booking-1", null, "listing-1",
                        "customer-1", "provider-1",
                        com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.CONFIRMED,
                        null, null));
        when(conversationRepository.findByChatKey("booking-1")).thenReturn(Optional.empty());

        service.getChatHistory("booking-1", 0, 20);
    }

    @Test
    void strangerCustomerCannotReadChatHistory() {
        loginAs("stranger-1", Role.CUSTOMER);
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants("booking-1", null, "listing-1",
                        "customer-1", "provider-1",
                        com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.CONFIRMED,
                        null, null));

        assertThatThrownBy(() -> service.getChatHistory("booking-1", 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdminCanReadChatHistoryButHiddenTextIsNull() {
        loginAs("super-1", Role.SUPER_ADMIN);
        when(bookingFacade.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants("booking-1", null, "listing-1",
                        "customer-1", "provider-1",
                        com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.CONFIRMED,
                        null, null));
        Conversation conversation = new Conversation();
        conversation.setId("conversation-1");
        conversation.setBookingId("booking-1");
        when(conversationRepository.findByChatKey("booking-1")).thenReturn(Optional.of(conversation));
        Message hidden = message();
        hidden.setStatus(MessageStatus.HIDDEN);
        hidden.setHideReason("Policy violation");
        when(messageRepository.findByConversationId(
                org.mockito.ArgumentMatchers.eq("conversation-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hidden)));

        MessagePageResponse response = service.getChatHistory("booking-1", 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).body()).isNull();
    }
}
