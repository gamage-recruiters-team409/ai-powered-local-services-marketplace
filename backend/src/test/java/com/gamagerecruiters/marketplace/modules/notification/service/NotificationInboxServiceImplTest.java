package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.mongodb.client.result.UpdateResult;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceImplTest {
    @Mock private NotificationRepository repository;
    @Mock private MongoTemplate mongoTemplate;
    private NotificationInboxServiceImpl service;

    @BeforeEach void setUp() { service = new NotificationInboxServiceImpl(repository, mongoTemplate, new NotificationTextBuilder()); login("user-1"); }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void listMineSortsNewestFirstCapsSizeAndUsesDefaultSize() {
        when(repository.findByUserId(eq("user-1"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.listMine(false, 2, 100);
        service.listMine(false, 0, 0);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findByUserId(eq("user-1"), pages.capture());
        assertThat(pages.getAllValues().get(0).getPageSize()).isEqualTo(50);
        assertThat(pages.getAllValues().get(0).getSort().toString()).contains("createdAt: DESC", "id: DESC");
        assertThat(pages.getAllValues().get(1).getPageSize()).isEqualTo(20);
    }

    @Test void listMineUsesUnreadRepositoryAndRejectsNegativePage() {
        when(repository.findByUserIdAndReadAtIsNull(eq("user-1"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.listMine(true, 0, 20);
        verify(repository).findByUserIdAndReadAtIsNull(eq("user-1"), any(Pageable.class));
        assertThatThrownBy(() -> service.listMine(false, -1, 20)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo("INVALID_PAGINATION"));
    }

    @Test void unauthenticatedListReturns401WithoutRepositoryRead() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.listMine(false, 0, 20)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(401));
        verifyNoInteractions(repository);
    }

    @Test void markReadUsesOwnedUnreadConditionalQueryAndReturnsReadNotification() {
        Notification notification = notification("notification-1", "user-1", Instant.now());
        when(mongoTemplate.updateFirst(any(Query.class), any(), eq(Notification.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.findOne(any(Query.class), eq(Notification.class))).thenReturn(notification);
        var response = service.markRead("notification-1");
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(query.capture(), any(), eq(Notification.class));
        assertThat(query.getValue().getQueryObject().toJson()).contains("notification-1", "user-1", "readAt");
        assertThat(response.read()).isTrue();
    }

    @Test void alreadyReadNotificationIsReturnedWithItsFirstReadTimeUnchanged() {
        Instant firstRead = Instant.parse("2026-01-01T00:00:00Z");
        Notification notification = notification("notification-1", "user-1", firstRead);
        when(mongoTemplate.updateFirst(any(Query.class), any(), eq(Notification.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongoTemplate.findOne(any(Query.class), eq(Notification.class))).thenReturn(notification);
        var response = service.markRead("notification-1");
        assertThat(response.readAt()).isEqualTo(firstRead);
        assertThat(response.read()).isTrue();
    }

    @Test void unknownOrOtherUserNotificationReturnsNotFoundWithoutFurtherUpdate() {
        when(mongoTemplate.updateFirst(any(Query.class), any(), eq(Notification.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongoTemplate.findOne(any(Query.class), eq(Notification.class))).thenReturn(null);
        assertThatThrownBy(() -> service.markRead("missing")).isInstanceOf(ResourceNotFoundException.class)
                .satisfies(error -> assertThat(((ResourceNotFoundException) error).getErrorCode()).isEqualTo("NOTIFICATION_NOT_FOUND"));
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(), eq(Notification.class));
    }

    @Test void markAllReadAndUnreadCountAreScopedToCurrentUser() {
        when(mongoTemplate.updateMulti(any(Query.class), any(), eq(Notification.class))).thenReturn(UpdateResult.acknowledged(3, 3L, null));
        when(mongoTemplate.count(any(Query.class), eq(Notification.class))).thenReturn(4L);
        assertThat(service.markAllRead()).isEqualTo(3L);
        assertThat(service.unreadCount()).isEqualTo(4L);
        ArgumentCaptor<Query> updateQuery = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Query> countQuery = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateMulti(updateQuery.capture(), any(), eq(Notification.class));
        verify(mongoTemplate).count(countQuery.capture(), eq(Notification.class));
        assertThat(updateQuery.getValue().getQueryObject().toJson()).contains("user-1", "readAt");
        assertThat(countQuery.getValue().getQueryObject().toJson()).contains("user-1", "readAt");
    }

    @Test void oldFreeTextReasonIsRemovedFromInboxResponseAndText() {
        String legacyReason = "Please visit Blue House on Temple Road in Nugegoda";
        Notification notification = notification("notification-1", "user-1", null);
        notification.setType(NotificationType.BOOKING_REQUEST_RECEIVED);
        notification.setData(Map.of("reason", legacyReason, "district", "Nugegoda"));
        when(repository.findByUserId(eq("user-1"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(notification)));

        var response = service.listMine(false, 0, 20).content().get(0);

        assertThat(response.data()).doesNotContainKey("reason");
        assertThat(response.data()).containsOnly(Map.entry("district", "Nugegoda"));
        assertThat(response.title()).doesNotContain(legacyReason);
        assertThat(response.message()).doesNotContain(legacyReason);
        assertThat(response.message()).contains("Nugegoda");
    }

    @Test void validReasonCodeIsRetainedInInboxResponse() {
        Notification notification = notification("notification-1", "user-1", null);
        notification.setData(Map.of("reason", "PROVIDER_UNAVAILABLE"));
        when(repository.findByUserId(eq("user-1"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(notification)));

        var response = service.listMine(false, 0, 20).content().get(0);

        assertThat(response.data()).containsEntry("reason", "PROVIDER_UNAVAILABLE");
        assertThat(response.message()).contains("The provider is unavailable at that time.");
    }

    private static Notification notification(String id, String userId, Instant readAt) { Notification notification = new Notification(); notification.setId(id); notification.setUserId(userId); notification.setType(NotificationType.BOOKING_CONFIRMED); notification.setCreatedAt(Instant.now()); notification.setReadAt(readAt); return notification; }
    private static void login(String userId) { UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", Role.CUSTOMER); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())); }
}
