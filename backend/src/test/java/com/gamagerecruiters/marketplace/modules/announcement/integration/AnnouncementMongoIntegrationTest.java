package com.gamagerecruiters.marketplace.modules.announcement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementIntent;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementPageResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementRequest;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;
import com.gamagerecruiters.marketplace.modules.announcement.port.AnnouncementDeliveryPort;
import com.gamagerecruiters.marketplace.modules.announcement.service.AnnouncementPublisher;
import com.gamagerecruiters.marketplace.modules.announcement.service.AnnouncementService;
import com.gamagerecruiters.marketplace.modules.announcement.service.AnnouncementStore;

/**
 * Announcements against a REAL MongoDB in a throw-away Testcontainers instance (never Atlas or the shared database).
 * Everything is real except that the store and the delivery stub are spied to count calls and to interleave a
 * publisher run between an admin's read and write. Refuses to run against a database named "marketplace".
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        // The test runs the publisher itself; no background runs.
        "app.announcement.publisher.enabled=false",
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false"
})
class AnnouncementMongoIntegrationTest {

    private static final String TEST_DATABASE = "announcement_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String ADMIN = "admin-it";

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl(TEST_DATABASE);
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

    @MockitoSpyBean
    private AnnouncementStore store;
    @MockitoSpyBean
    private AnnouncementDeliveryPort deliveryPort;

    @Autowired
    private AnnouncementService service;
    @Autowired
    private AnnouncementPublisher publisher;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes announcements and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Announcement.class);
    }

    // ---------- helpers ----------

    private static AnnouncementRequest request(String title, AnnouncementAudience audience, AnnouncementIntent intent,
                                               Instant scheduledAt, boolean pin) {
        return new AnnouncementRequest(title, "Message for " + title, audience, scheduledAt, false, pin, intent);
    }

    /** A SCHEDULED announcement whose time has already come (scheduledAt moved into the past directly in the DB). */
    private String dueScheduled(String title, boolean pin) {
        String id = service.create(ADMIN, request(title, AnnouncementAudience.ALL_USERS, AnnouncementIntent.PUBLISH,
                Instant.now().plus(Duration.ofHours(1)), pin)).id();
        Announcement a = mongoTemplate.findById(id, Announcement.class);
        a.setScheduledAt(Instant.now().minusSeconds(5));
        mongoTemplate.save(a);
        return id;
    }

    private Announcement load(String id) {
        return mongoTemplate.findById(id, Announcement.class);
    }

    private static void assertApiError(Throwable thrown, int status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    /**
     * The FIRST findById returns the document as the admin read it, but before it returns, {@code meanwhile} changes
     * it in the database (e.g. the publisher publishes it). Later reads are normal.
     */
    private void interleaveAfterFirstRead(Runnable meanwhile) {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            if (first.getAndSet(false)) {
                meanwhile.run();
            }
            return read;
        }).when(store).findById(any());
    }

    // ---------- publisher ----------

    @Test
    @DisplayName("publisher: two instances at the same moment publish each due announcement exactly once")
    void publisherPublishesOnceUnderConcurrentRuns() throws Exception {
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            due.add(dueScheduled("Due " + i, i % 2 == 0));
        }
        String future = service.create(ADMIN, request("Future", AnnouncementAudience.ALL_USERS, AnnouncementIntent.PUBLISH,
                Instant.now().plus(Duration.ofHours(2)), false)).id();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> run = () -> {
                    start.await();
                    return publisher.publishDue();
                };
                runs.add(pool.submit(run));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).as("published exactly once in total").isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < due.size(); i++) {
            Announcement a = load(due.get(i));
            assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
            assertThat(a.getPublishedAt()).isNotNull();
            assertThat(a.getDeliveryState()).isEqualTo(DeliveryState.NOT_APPLIED);
            if (a.isPinToHeader()) {
                assertThat(a.getPinExpiresAt()).isEqualTo(a.getPublishedAt().plus(Duration.ofHours(48)));
            } else {
                assertThat(a.getPinExpiresAt()).isNull();
            }
        }
        assertThat(load(future).getStatus()).as("not due yet").isEqualTo(AnnouncementStatus.SCHEDULED);
        verify(deliveryPort, times(6)).deliver(any());

        assertThat(publisher.publishDue()).as("a later run publishes nothing").isZero();
        verify(deliveryPort, times(6)).deliver(any());
    }

    // ---------- races between admin edits/deletes and the publisher ----------

    @Test
    @DisplayName("edit race: the publisher publishes between the admin's read and write -> 409 STATE_CONFLICT, "
            + "the published announcement is unchanged")
    void editRacingWithPublisher() {
        String id = dueScheduled("Race edit", false);
        interleaveAfterFirstRead(() -> publisher.publishDue());

        assertApiError(catchThrowable(() -> service.update(ADMIN, id, request("Edited", AnnouncementAudience.CUSTOMERS,
                AnnouncementIntent.DRAFT, null, false))), 409, "ANNOUNCEMENT_STATE_CONFLICT");

        Announcement stored = load(id);
        assertThat(stored.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(stored.getTitle()).isEqualTo("Race edit");
        assertThat(stored.getAudience()).isEqualTo(AnnouncementAudience.ALL_USERS);
    }

    @Test
    @DisplayName("delete race: the publisher publishes between the admin's read and delete -> 409 STATE_CONFLICT, "
            + "nothing deleted")
    void deleteRacingWithPublisher() {
        String id = dueScheduled("Race delete", false);
        interleaveAfterFirstRead(() -> publisher.publishDue());

        assertApiError(catchThrowable(() -> service.delete(ADMIN, id)), 409, "ANNOUNCEMENT_STATE_CONFLICT");
        assertThat(load(id)).isNotNull();
        assertThat(load(id).getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
    }

    @Test
    @DisplayName("edit race: another admin saves the draft between this admin's read and write -> 409 UPDATE_CONFLICT")
    void editRacingWithAnotherAdmin() {
        String id = service.create(ADMIN, request("Draft", AnnouncementAudience.ALL_USERS, AnnouncementIntent.DRAFT,
                null, false)).id();
        Announcement snapshot = load(id);
        interleaveAfterFirstRead(() -> store.replaceUnpublished(snapshot, otherAdminChange(snapshot)));

        assertApiError(catchThrowable(() -> service.update(ADMIN, id, request("Mine", AnnouncementAudience.ALL_USERS,
                AnnouncementIntent.DRAFT, null, false))), 409, "ANNOUNCEMENT_UPDATE_CONFLICT");
        assertThat(load(id).getTitle()).isEqualTo("Theirs");
    }

    private static Announcement otherAdminChange(Announcement snapshot) {
        Announcement other = new Announcement();
        other.setTitle("Theirs");
        other.setMessage(snapshot.getMessage());
        other.setAudience(snapshot.getAudience());
        other.setStatus(AnnouncementStatus.DRAFT);
        other.setDeliveryState(DeliveryState.NOT_APPLIED);
        other.setUpdatedBy("other-admin");
        other.setUpdatedAt(Instant.now());
        return other;
    }

    // ---------- status rules on real data ----------

    @Test
    @DisplayName("published: only title/message can change (no re-delivery); other fields 409; delete 409")
    void publishedRules() {
        String id = service.create(ADMIN, request("Live", AnnouncementAudience.PROVIDERS, AnnouncementIntent.PUBLISH,
                null, true)).id();
        verify(deliveryPort, times(1)).deliver(any());

        service.update(ADMIN, id, new AnnouncementRequest("Live (fixed)", "Corrected text", AnnouncementAudience.PROVIDERS,
                null, false, true, AnnouncementIntent.PUBLISH));
        assertThat(load(id).getTitle()).isEqualTo("Live (fixed)");
        verify(deliveryPort, times(1)).deliver(any());

        assertApiError(catchThrowable(() -> service.update(ADMIN, id, new AnnouncementRequest("Live", "x",
                AnnouncementAudience.ALL_USERS, null, false, true, AnnouncementIntent.PUBLISH))), 409,
                "ANNOUNCEMENT_FIELDS_LOCKED");
        assertApiError(catchThrowable(() -> service.delete(ADMIN, id)), 409, "ANNOUNCEMENT_NOT_DELETABLE");
        verify(store, never()).deleteUnpublished(any());
        assertThat(load(id).getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
    }

    // ---------- list, search escaping, pagination, summary ----------

    @Test
    @DisplayName("list: status/audience filters, escaped title search, pagination counts; summary counts")
    void listSearchAndSummary() {
        service.create(ADMIN, request("Sale 50% (today only)", AnnouncementAudience.CUSTOMERS, AnnouncementIntent.PUBLISH, null, true));
        service.create(ADMIN, request("Sale 50 today", AnnouncementAudience.CUSTOMERS, AnnouncementIntent.PUBLISH, null, false));
        service.create(ADMIN, request("Provider payout update", AnnouncementAudience.PROVIDERS, AnnouncementIntent.DRAFT, null, false));
        service.create(ADMIN, request("Holiday hours", AnnouncementAudience.ALL_USERS, AnnouncementIntent.PUBLISH,
                Instant.now().plus(Duration.ofDays(1)), false));
        service.create(ADMIN, request("Regex .* title", AnnouncementAudience.ALL_USERS, AnnouncementIntent.DRAFT, null, false));
        service.create(ADMIN, request("New feature", AnnouncementAudience.ALL_USERS, AnnouncementIntent.DRAFT, null, false));
        service.create(ADMIN, request("Maintenance window", AnnouncementAudience.ALL_USERS, AnnouncementIntent.DRAFT, null, false));

        AnnouncementPageResponse<AnnouncementResponse> first = service.list(null, null, null, 0, 5);
        AnnouncementPageResponse<AnnouncementResponse> second = service.list(null, null, null, 1, 5);
        assertThat(first.totalElements()).isEqualTo(7);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).hasSize(5);
        assertThat(second.content()).hasSize(2);
        assertThat(first.content().get(0).title()).as("newest first").isEqualTo("Maintenance window");

        // Regex characters are literal: "(today" and ".*" match only titles that contain them.
        assertThat(service.list(null, null, "50% (today", 0, 5).content())
                .singleElement().satisfies(a -> assertThat(a.title()).isEqualTo("Sale 50% (today only)"));
        assertThat(service.list(null, null, ".*", 0, 5).content())
                .singleElement().satisfies(a -> assertThat(a.title()).isEqualTo("Regex .* title"));
        assertThat(service.list(null, null, "SALE", 0, 5).totalElements()).as("case-insensitive").isEqualTo(2);

        assertThat(service.list("PUBLISHED", "CUSTOMERS", null, 0, 5).totalElements()).isEqualTo(2);
        assertThat(service.list("DRAFT", null, null, 0, 5).totalElements()).isEqualTo(4);
        assertThat(service.list(null, "PROVIDERS", null, 0, 5).totalElements()).isEqualTo(1);

        var summary = service.summary();
        assertThat(summary.active()).as("published + pinned + pin not expired").isEqualTo(1);
        assertThat(summary.scheduled()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(7);
        assertThat(summary.tabs().all()).isEqualTo(7);
        assertThat(summary.tabs().published()).isEqualTo(2);
        assertThat(summary.tabs().drafts()).isEqualTo(4);
    }
}
