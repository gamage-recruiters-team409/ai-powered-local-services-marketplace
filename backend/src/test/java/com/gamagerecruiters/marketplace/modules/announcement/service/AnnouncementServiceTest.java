package com.gamagerecruiters.marketplace.modules.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementIntent;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementRequest;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;
import com.gamagerecruiters.marketplace.modules.announcement.port.AnnouncementDeliveryPort;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Status rules, locked fields, delete rules and pin expiry (store and delivery mocked). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnouncementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String ADMIN = "admin-1";
    private static final String ID = "ann-1";
    private static final String TITLE = "Planned maintenance";
    private static final String MESSAGE = "We will be **offline** on Sunday.";

    @Mock
    private AnnouncementStore store;
    @Mock
    private AnnouncementDeliveryPort deliveryPort;

    private AnnouncementService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new AnnouncementService(store, deliveryPort, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(48));
        when(store.insert(any(Announcement.class))).thenAnswer(inv -> {
            Announcement a = inv.getArgument(0);
            a.setId(ID);
            a.setVersion(0L);
            return a;
        });
        when(deliveryPort.deliver(any())).thenReturn(DeliveryState.NOT_APPLIED);
        serviceLogger = (Logger) LoggerFactory.getLogger(AnnouncementService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private static AnnouncementRequest request(AnnouncementIntent intent, Instant scheduledAt, boolean pin) {
        return new AnnouncementRequest("  " + TITLE + " ", MESSAGE, AnnouncementAudience.CUSTOMERS, scheduledAt, true,
                pin, intent);
    }

    private Announcement stored(AnnouncementStatus status) {
        Announcement a = new Announcement();
        a.setId(ID);
        a.setVersion(3L);
        a.setTitle(TITLE);
        a.setMessage(MESSAGE);
        a.setAudience(AnnouncementAudience.CUSTOMERS);
        a.setStatus(status);
        a.setSendPush(true);
        a.setPinToHeader(false);
        a.setDeliveryState(DeliveryState.NOT_APPLIED);
        if (status == AnnouncementStatus.PUBLISHED) {
            a.setPublishedAt(NOW.minusSeconds(3600));
        }
        when(store.findById(ID)).thenReturn(Optional.of(a));
        return a;
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // ---------- create: draft / publish now / scheduled ----------

    @Test
    @DisplayName("intent DRAFT -> DRAFT, no publishedAt, nothing delivered; createdBy/updatedBy = the admin; title trimmed")
    void draft() {
        AnnouncementResponse result = service.create(ADMIN, request(AnnouncementIntent.DRAFT, NOW.plusSeconds(600), true));

        assertThat(result.status()).isEqualTo(AnnouncementStatus.DRAFT);
        assertThat(result.publishedAt()).isNull();
        assertThat(result.pinExpiresAt()).isNull();
        assertThat(result.scheduledAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(result.title()).isEqualTo(TITLE);
        assertThat(result.message()).as("markdown stored raw").isEqualTo(MESSAGE);
        assertThat(result.createdBy()).isEqualTo(ADMIN);
        assertThat(result.updatedBy()).isEqualTo(ADMIN);
        assertThat(result.deliveryState()).isEqualTo(DeliveryState.NOT_APPLIED);
        verifyNoInteractions(deliveryPort);
    }

    @Test
    @DisplayName("intent PUBLISH without scheduledAt -> PUBLISHED now, delivered once, delivery stays NOT_APPLIED")
    void publishNow() {
        AnnouncementResponse result = service.create(ADMIN, request(AnnouncementIntent.PUBLISH, null, false));

        assertThat(result.status()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(result.publishedAt()).isEqualTo(NOW);
        assertThat(result.pinExpiresAt()).isNull();
        assertThat(result.deliveryState()).isEqualTo(DeliveryState.NOT_APPLIED);
        verify(deliveryPort).deliver(new AnnouncementDeliveryPort.Delivery(ID, AnnouncementAudience.CUSTOMERS, true, false));
    }

    @ParameterizedTest(name = "scheduledAt = now + {0}s -> published now")
    @ValueSource(longs = {-3600, 0, 30, 60})
    @DisplayName("intent PUBLISH with scheduledAt in the past or at most 1 minute ahead -> published now")
    void publishNowWithinThreshold(long seconds) {
        assertThat(service.create(ADMIN, request(AnnouncementIntent.PUBLISH, NOW.plusSeconds(seconds), false)).status())
                .isEqualTo(AnnouncementStatus.PUBLISHED);
    }

    @Test
    @DisplayName("intent PUBLISH with scheduledAt more than 1 minute ahead -> SCHEDULED, not delivered, no pin expiry yet")
    void scheduled() {
        AnnouncementResponse result = service.create(ADMIN, request(AnnouncementIntent.PUBLISH, NOW.plusSeconds(61), true));

        assertThat(result.status()).isEqualTo(AnnouncementStatus.SCHEDULED);
        assertThat(result.publishedAt()).isNull();
        assertThat(result.pinExpiresAt()).as("set when it is published").isNull();
        verifyNoInteractions(deliveryPort);
    }

    @Test
    @DisplayName("pinned and published now -> pinExpiresAt = publishedAt + pin duration (default 48h, configurable)")
    void pinExpiry() {
        assertThat(service.create(ADMIN, request(AnnouncementIntent.PUBLISH, null, true)).pinExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofHours(48)));

        AnnouncementService twoHours = new AnnouncementService(store, deliveryPort, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(2));
        AnnouncementResponse result = twoHours.create(ADMIN, request(AnnouncementIntent.PUBLISH, null, true));
        assertThat(result.pinExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(result.pinActive()).isTrue();
    }

    // ---------- update ----------

    @Test
    @DisplayName("PUBLISHED: changing anything except title/message -> 409 ANNOUNCEMENT_FIELDS_LOCKED, nothing stored")
    void publishedFieldsLocked() {
        stored(AnnouncementStatus.PUBLISHED);
        List<AnnouncementRequest> changes = List.of(
                new AnnouncementRequest(TITLE, MESSAGE, AnnouncementAudience.PROVIDERS, null, true, false, AnnouncementIntent.PUBLISH),
                new AnnouncementRequest(TITLE, MESSAGE, AnnouncementAudience.CUSTOMERS, null, false, false, AnnouncementIntent.PUBLISH),
                new AnnouncementRequest(TITLE, MESSAGE, AnnouncementAudience.CUSTOMERS, null, true, true, AnnouncementIntent.PUBLISH),
                new AnnouncementRequest(TITLE, MESSAGE, AnnouncementAudience.CUSTOMERS, NOW, true, false, AnnouncementIntent.PUBLISH),
                new AnnouncementRequest(TITLE, MESSAGE, AnnouncementAudience.CUSTOMERS, null, true, false, AnnouncementIntent.DRAFT));

        for (AnnouncementRequest change : changes) {
            assertApiError(catchThrowable(() -> service.update(ADMIN, ID, change)), HttpStatus.CONFLICT,
                    "ANNOUNCEMENT_FIELDS_LOCKED");
        }
        verify(store, never()).updatePublishedText(any(), any(), any(), any(), any());
        verify(store, never()).replaceUnpublished(any(), any());
    }

    @Test
    @DisplayName("PUBLISHED: title/message correction is saved and NOT re-delivered")
    void publishedTextCorrection() {
        Announcement current = stored(AnnouncementStatus.PUBLISHED);
        when(store.updatePublishedText(eq(current), anyString(), anyString(), eq(ADMIN), eq(NOW)))
                .thenReturn(Optional.of(current));

        service.update(ADMIN, ID, new AnnouncementRequest(" Fixed title ", "Fixed *text*", AnnouncementAudience.CUSTOMERS,
                null, true, false, AnnouncementIntent.PUBLISH));

        verify(store).updatePublishedText(current, "Fixed title", "Fixed *text*", ADMIN, NOW);
        verifyNoInteractions(deliveryPort);
    }

    @Test
    @DisplayName("DRAFT -> PUBLISH: published now with the status/version read, then delivered")
    void draftPublished() {
        Announcement current = stored(AnnouncementStatus.DRAFT);
        when(store.replaceUnpublished(eq(current), any())).thenAnswer(inv -> Optional.of(inv.getArgument(1)));

        AnnouncementResponse result = service.update(ADMIN, ID, request(AnnouncementIntent.PUBLISH, null, true));

        assertThat(result.status()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(result.pinExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        verify(deliveryPort).deliver(any());
    }

    @Test
    @DisplayName("edit race: conditional update empty and the status changed (e.g. just published) -> 409 STATE_CONFLICT")
    void editRaceWithPublisher() {
        Announcement read = stored(AnnouncementStatus.SCHEDULED);
        Announcement published = new Announcement();
        published.setStatus(AnnouncementStatus.PUBLISHED);
        when(store.findById(ID)).thenReturn(Optional.of(read), Optional.of(published));
        when(store.replaceUnpublished(any(), any())).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.update(ADMIN, ID, request(AnnouncementIntent.DRAFT, null, false))),
                HttpStatus.CONFLICT, "ANNOUNCEMENT_STATE_CONFLICT");
        verifyNoInteractions(deliveryPort);
    }

    @Test
    @DisplayName("edit race: same status but a newer version (another admin) -> 409 ANNOUNCEMENT_UPDATE_CONFLICT")
    void editRaceWithAnotherAdmin() {
        stored(AnnouncementStatus.DRAFT);
        when(store.replaceUnpublished(any(), any())).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.update(ADMIN, ID, request(AnnouncementIntent.DRAFT, null, false))),
                HttpStatus.CONFLICT, "ANNOUNCEMENT_UPDATE_CONFLICT");
    }

    @Test
    @DisplayName("unknown id -> 404 ANNOUNCEMENT_NOT_FOUND")
    void notFound() {
        when(store.findById(ID)).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.get(ID)), HttpStatus.NOT_FOUND, "ANNOUNCEMENT_NOT_FOUND");
    }

    // ---------- delete ----------

    @Test
    @DisplayName("delete: PUBLISHED -> 409 ANNOUNCEMENT_NOT_DELETABLE; DRAFT and SCHEDULED are deleted")
    void deleteRules() {
        stored(AnnouncementStatus.PUBLISHED);
        assertApiError(catchThrowable(() -> service.delete(ADMIN, ID)), HttpStatus.CONFLICT, "ANNOUNCEMENT_NOT_DELETABLE");
        verify(store, never()).deleteUnpublished(any());

        Announcement draft = stored(AnnouncementStatus.DRAFT);
        when(store.deleteUnpublished(draft)).thenReturn(true);
        service.delete(ADMIN, ID);
        verify(store).deleteUnpublished(draft);

        Announcement scheduled = stored(AnnouncementStatus.SCHEDULED);
        when(store.deleteUnpublished(scheduled)).thenReturn(true);
        service.delete(ADMIN, ID);
        verify(store).deleteUnpublished(scheduled);
    }

    @Test
    @DisplayName("delete race: published by the publisher meanwhile -> 409 ANNOUNCEMENT_STATE_CONFLICT")
    void deleteRace() {
        Announcement read = stored(AnnouncementStatus.SCHEDULED);
        Announcement published = new Announcement();
        published.setStatus(AnnouncementStatus.PUBLISHED);
        when(store.findById(ID)).thenReturn(Optional.of(read), Optional.of(published));
        when(store.deleteUnpublished(read)).thenReturn(false);

        assertApiError(catchThrowable(() -> service.delete(ADMIN, ID)), HttpStatus.CONFLICT, "ANNOUNCEMENT_STATE_CONFLICT");
    }

    // ---------- list ----------

    @Test
    @DisplayName("list: q is escaped with Pattern.quote (regex characters are literal), case-insensitive")
    void searchIsEscaped() {
        service.list(null, null, "50% (off).*", 0, 5);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(store).find(query.capture(), any(Pageable.class));
        String filter = query.getValue().getQueryObject().toJson();
        assertThat(filter).contains("\\\\Q50% (off).*\\\\E");
    }

    @Test
    @DisplayName("list: bad filters and page limits -> 400 with clear codes")
    void listValidation() {
        assertApiError(catchThrowable(() -> service.list("LIVE", null, null, 0, 5)), HttpStatus.BAD_REQUEST,
                "INVALID_STATUS_FILTER");
        assertApiError(catchThrowable(() -> service.list(null, "EVERYONE", null, 0, 5)), HttpStatus.BAD_REQUEST,
                "INVALID_AUDIENCE_FILTER");
        assertApiError(catchThrowable(() -> service.list(null, null, "x".repeat(81), 0, 5)), HttpStatus.BAD_REQUEST,
                "INVALID_SEARCH_TEXT");
        assertApiError(catchThrowable(() -> service.list(null, null, null, -1, 5)), HttpStatus.BAD_REQUEST,
                "INVALID_PAGE_REQUEST");
        assertApiError(catchThrowable(() -> service.list(null, null, null, 0, 101)), HttpStatus.BAD_REQUEST,
                "INVALID_PAGE_REQUEST");
    }

    @Test
    @DisplayName("logs contain ids only: never the title or the message")
    void logsHaveNoContent() {
        service.create(ADMIN, request(AnnouncementIntent.PUBLISH, null, true));

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(line -> assertThat(line).doesNotContain(TITLE).doesNotContain("offline"));
    }
}
