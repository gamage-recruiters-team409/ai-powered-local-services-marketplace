package com.gamagerecruiters.marketplace.modules.notification.integration;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
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
import org.testcontainers.containers.MongoDBContainer;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationInboxService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class NotificationMongoIntegrationTest {
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");
    @DynamicPropertySource static void configureMongoUri(DynamicPropertyRegistry registry) { registry.add("spring.data.mongodb.uri", () -> { if (!mongoDBContainer.isRunning()) mongoDBContainer.start(); return mongoDBContainer.getReplicaSetUrl(); }); }
    @BeforeAll static void startContainer() { if (!mongoDBContainer.isRunning()) mongoDBContainer.start(); }
    @AfterAll static void stopContainer() { if (mongoDBContainer.isRunning()) mongoDBContainer.stop(); }
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private NotificationInboxService inbox;
    @BeforeEach void cleanData() { mongoTemplate.remove(new Query(), Notification.class); }
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void secondUserCannotMarkAnotherUsersNotificationButOwnerCanMarkIt() {
        Notification notification = insert("owner", "one", Instant.now(), Map.of()); login("other");
        assertThatThrownBy(() -> inbox.markRead(notification.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(mongoTemplate.findById(notification.getId(), Notification.class).getReadAt()).isNull();
        login("owner"); assertThat(inbox.markRead(notification.getId()).read()).isTrue();
    }
    @Test void markAllReadOnlyChangesCurrentUsersNotifications() {
        Notification mine = insert("owner", "one", Instant.now(), Map.of()); Notification theirs = insert("other", "two", Instant.now(), Map.of()); login("owner");
        assertThat(inbox.markAllRead()).isEqualTo(1); assertThat(mongoTemplate.findById(mine.getId(), Notification.class).getReadAt()).isNotNull(); assertThat(mongoTemplate.findById(theirs.getId(), Notification.class).getReadAt()).isNull();
    }
    @Test void pagingHasStableOrderWithoutRepeatsOrGapsForMatchingTimestamps() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z"); for (int i = 0; i < 5; i++) insert("owner", "booking-" + i, time, Map.of()); login("owner");
        List<String> firstRun = java.util.stream.Stream.of(inbox.listMine(false, 0, 2), inbox.listMine(false, 1, 2), inbox.listMine(false, 2, 2)).flatMap(page -> page.content().stream()).map(item -> item.id()).toList();
        List<String> secondRun = java.util.stream.Stream.of(inbox.listMine(false, 0, 2), inbox.listMine(false, 1, 2), inbox.listMine(false, 2, 2)).flatMap(page -> page.content().stream()).map(item -> item.id()).toList();
        assertThat(firstRun).hasSize(5).doesNotHaveDuplicates(); assertThat(secondRun).containsExactlyElementsOf(firstRun);
    }
    @Test void unreadCountIsPerUserAndDataMapRoundTrips() {
        Notification saved = insert("owner", "one", Instant.now(), Map.of("district", "Kandy")); insert("other", "two", Instant.now(), Map.of()); login("owner");
        assertThat(inbox.unreadCount()).isEqualTo(1); assertThat(mongoTemplate.findById(saved.getId(), Notification.class).getData()).containsEntry("district", "Kandy");
    }
    private Notification insert(String userId, String bookingId, Instant createdAt, Map<String, String> data) { Notification notification = new Notification(); notification.setUserId(userId); notification.setBookingId(bookingId); notification.setType(NotificationType.BOOKING_CONFIRMED); notification.setCreatedAt(createdAt); notification.setData(data); return mongoTemplate.insert(notification); }
    private static void login(String userId) { UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", Role.CUSTOMER); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())); }
}
