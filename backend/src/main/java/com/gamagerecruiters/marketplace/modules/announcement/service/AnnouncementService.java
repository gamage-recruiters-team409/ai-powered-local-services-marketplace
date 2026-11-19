package com.gamagerecruiters.marketplace.modules.announcement.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementRules;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementIntent;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementPageResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementRequest;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementSummaryResponse;
import com.gamagerecruiters.marketplace.modules.announcement.mapper.AnnouncementMapper;
import com.gamagerecruiters.marketplace.modules.announcement.port.AnnouncementDeliveryPort;

/**
 * Admin announcements. Role checks happen in the controller; this service enforces the status rules:
 * <ul>
 *   <li>intent DRAFT: saved as DRAFT (scheduledAt optional).</li>
 *   <li>intent PUBLISH: scheduledAt more than 1 minute ahead: SCHEDULED (published later by
 *       {@link AnnouncementPublisher}); otherwise PUBLISHED now, with publishedAt, and delivered once.</li>
 *   <li>Pinned: pinExpiresAt = publishedAt + {@code app.announcement.pin-duration} (default PT48H).</li>
 *   <li>PUBLISHED: only title and message can be corrected (no re-delivery); any other change gets 409
 *       ANNOUNCEMENT_FIELDS_LOCKED. It cannot be deleted (409 ANNOUNCEMENT_NOT_DELETABLE).</li>
 *   <li>Every change is conditional on the status and version that were read: a race with the publisher or another
 *       admin gets 409 ANNOUNCEMENT_STATE_CONFLICT (status changed) or ANNOUNCEMENT_UPDATE_CONFLICT (same status,
 *       newer version).</li>
 * </ul>
 * Only ids are logged, never titles or messages.
 *
 * <p>TODO(audit): the admin module's AdminAuditLogService only records admin-account actions (its action enum and
 * fields are user-account specific). When a shared audit service exists, record create / update / publish / delete
 * here.</p>
 */
@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id"));

    private final AnnouncementStore store;
    private final AnnouncementDeliveryPort deliveryPort;
    private final Clock clock;
    private final Duration pinDuration;

    @Autowired
    public AnnouncementService(AnnouncementStore store, AnnouncementDeliveryPort deliveryPort,
                               @Value("${app.announcement.pin-duration:PT48H}") Duration pinDuration) {
        this(store, deliveryPort, Clock.systemUTC(), pinDuration);
    }

    AnnouncementService(AnnouncementStore store, AnnouncementDeliveryPort deliveryPort, Clock clock,
                        Duration pinDuration) {
        if (pinDuration == null || pinDuration.isNegative() || pinDuration.isZero()) {
            throw new IllegalArgumentException("app.announcement.pin-duration must be greater than zero");
        }
        this.store = store;
        this.deliveryPort = deliveryPort;
        this.clock = clock;
        this.pinDuration = pinDuration;
    }

    // ---------------------------------------------------------------- reads

    /** Newest first. {@code q} searches the title (case-insensitive); it is escaped, never used as a pattern. */
    public AnnouncementPageResponse<AnnouncementResponse> list(String status, String audience, String q, int page,
                                                               int size) {
        Pageable pageable = pageRequest(page, size);
        List<Criteria> filters = new ArrayList<>();
        if (hasText(status)) {
            filters.add(Criteria.where("status").is(parseEnum(AnnouncementStatus.class, status,
                    "INVALID_STATUS_FILTER", "Unknown announcement status")));
        }
        if (hasText(audience)) {
            filters.add(Criteria.where("audience").is(parseEnum(AnnouncementAudience.class, audience,
                    "INVALID_AUDIENCE_FILTER", "Unknown announcement audience")));
        }
        if (hasText(q)) {
            String text = q.strip();
            if (text.length() > AnnouncementRules.MAX_SEARCH_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_TEXT",
                        "q must be at most " + AnnouncementRules.MAX_SEARCH_LENGTH + " characters");
            }
            filters.add(Criteria.where("title").regex(Pattern.quote(text), "i"));
        }
        Query query = filters.isEmpty() ? new Query()
                : Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        long total = store.count(query);
        Instant now = clock.instant();
        List<AnnouncementResponse> content = store.find(query, pageable).stream()
                .map(a -> AnnouncementMapper.toResponse(a, now))
                .toList();
        return AnnouncementPageResponse.from(new PageImpl<>(content, pageable, total));
    }

    public AnnouncementSummaryResponse summary() {
        Instant now = clock.instant();
        long published = countStatus(AnnouncementStatus.PUBLISHED);
        long scheduled = countStatus(AnnouncementStatus.SCHEDULED);
        long drafts = countStatus(AnnouncementStatus.DRAFT);
        long total = store.count(new Query());
        long active = store.count(Query.query(Criteria.where("status").is(AnnouncementStatus.PUBLISHED)
                .and("pinToHeader").is(true)
                .and("pinExpiresAt").gt(now)));
        return new AnnouncementSummaryResponse(active, scheduled, total,
                new AnnouncementSummaryResponse.Tabs(total, published, scheduled, drafts));
    }

    public AnnouncementResponse get(String id) {
        return AnnouncementMapper.toResponse(find(id), clock.instant());
    }

    // ---------------------------------------------------------------- writes

    public AnnouncementResponse create(String adminId, AnnouncementRequest request) {
        Instant now = clock.instant();
        Announcement announcement = new Announcement();
        announcement.setCreatedBy(adminId);
        announcement.setCreatedAt(now);
        announcement.setDeliveryState(DeliveryState.NOT_APPLIED);
        apply(announcement, request, adminId, now);

        Announcement saved = store.insert(announcement);
        log.info("event=ANNOUNCEMENT_CREATED announcementId={} adminId={} status={}",
                saved.getId(), adminId, saved.getStatus());
        if (saved.getStatus() == AnnouncementStatus.PUBLISHED) {
            deliver(saved);
        }
        return AnnouncementMapper.toResponse(saved, now);
    }

    public AnnouncementResponse update(String adminId, String id, AnnouncementRequest request) {
        Announcement current = find(id);
        Instant now = clock.instant();

        if (current.getStatus() == AnnouncementStatus.PUBLISHED) {
            assertOnlyTextChanged(current, request);
            Announcement updated = store.updatePublishedText(current, request.title().strip(), request.message(),
                            adminId, now)
                    .orElseThrow(() -> conflictAfterRace(id, current));
            // Text corrections are never re-delivered.
            log.info("event=ANNOUNCEMENT_TEXT_CORRECTED announcementId={} adminId={}", id, adminId);
            return AnnouncementMapper.toResponse(updated, now);
        }

        Announcement changed = new Announcement();
        changed.setDeliveryState(current.getDeliveryState() == null ? DeliveryState.NOT_APPLIED
                : current.getDeliveryState());
        apply(changed, request, adminId, now);
        Announcement updated = store.replaceUnpublished(current, changed)
                .orElseThrow(() -> conflictAfterRace(id, current));
        log.info("event=ANNOUNCEMENT_UPDATED announcementId={} adminId={} status={}", id, adminId, updated.getStatus());
        if (updated.getStatus() == AnnouncementStatus.PUBLISHED) {
            deliver(updated);
        }
        return AnnouncementMapper.toResponse(updated, now);
    }

    public void delete(String adminId, String id) {
        Announcement current = find(id);
        if (current.getStatus() == AnnouncementStatus.PUBLISHED) {
            throw new ResourceConflictException("ANNOUNCEMENT_NOT_DELETABLE",
                    "A published announcement cannot be deleted");
        }
        if (!store.deleteUnpublished(current)) {
            throw conflictAfterRace(id, current);
        }
        log.info("event=ANNOUNCEMENT_DELETED announcementId={} adminId={}", id, adminId);
    }

    // ---------------------------------------------------------------- rules

    /** Sets the content and the status from the intent: DRAFT, SCHEDULED, or PUBLISHED now (+ pin expiry). */
    private void apply(Announcement target, AnnouncementRequest request, String adminId, Instant now) {
        target.setTitle(request.title().strip());
        target.setMessage(request.message());
        target.setAudience(request.audience());
        target.setScheduledAt(truncate(request.scheduledAt()));
        target.setSendPush(request.push());
        target.setPinToHeader(request.pinned());
        target.setUpdatedBy(adminId);
        target.setUpdatedAt(now);
        target.setPublishedAt(null);
        target.setPinExpiresAt(null);

        if (request.intent() == AnnouncementIntent.DRAFT) {
            target.setStatus(AnnouncementStatus.DRAFT);
        } else if (isScheduled(request.scheduledAt(), now)) {
            target.setStatus(AnnouncementStatus.SCHEDULED);
        } else {
            target.setStatus(AnnouncementStatus.PUBLISHED);
            target.setPublishedAt(now);
            target.setPinExpiresAt(request.pinned() ? pinExpiry(now) : null);
        }
    }

    /** PUBLISH with a scheduledAt more than 1 minute ahead schedules it; otherwise it is published now. */
    static boolean isScheduled(Instant scheduledAt, Instant now) {
        return scheduledAt != null && scheduledAt.isAfter(now.plus(AnnouncementRules.SCHEDULE_THRESHOLD));
    }

    Instant pinExpiry(Instant publishedAt) {
        return publishedAt.plus(pinDuration);
    }

    /** A published announcement accepts only title/message changes; everything else must be sent unchanged. */
    private static void assertOnlyTextChanged(Announcement current, AnnouncementRequest request) {
        boolean locked = request.intent() != AnnouncementIntent.PUBLISH
                || request.audience() != current.getAudience()
                || !Objects.equals(truncate(request.scheduledAt()), truncate(current.getScheduledAt()))
                || request.push() != current.isSendPush()
                || request.pinned() != current.isPinToHeader();
        if (locked) {
            throw new ResourceConflictException("ANNOUNCEMENT_FIELDS_LOCKED",
                    "A published announcement can only have its title and message edited");
        }
    }

    /** The conditional write found nothing: tell a status change apart from a newer version. */
    private ApiException conflictAfterRace(String id, Announcement read) {
        Announcement now = store.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ANNOUNCEMENT_NOT_FOUND", "Announcement not found"));
        if (now.getStatus() != read.getStatus()) {
            return new ResourceConflictException("ANNOUNCEMENT_STATE_CONFLICT",
                    "The announcement status changed (for example it was just published). Reload it and try again");
        }
        return new ResourceConflictException("ANNOUNCEMENT_UPDATE_CONFLICT",
                "The announcement was changed by another request. Reload it and try again");
    }

    /** Delivery never undoes a publication; failures are logged by id only. */
    void deliver(Announcement published) {
        try {
            deliveryPort.deliver(new AnnouncementDeliveryPort.Delivery(published.getId(), published.getAudience(),
                    published.isSendPush(), published.isPinToHeader()));
        } catch (RuntimeException e) {
            log.error("event=ANNOUNCEMENT_DELIVERY_FAILED announcementId={} cause={}",
                    published.getId(), e.getClass().getSimpleName());
        }
    }

    private Announcement find(String id) {
        return store.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ANNOUNCEMENT_NOT_FOUND", "Announcement not found"));
    }

    private long countStatus(AnnouncementStatus status) {
        return store.count(Query.query(Criteria.where("status").is(status)));
    }

    /** MongoDB stores milliseconds; compare and store at that precision. */
    private static Instant truncate(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    private static Pageable pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > AnnouncementRules.MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST",
                    "page must be 0 or more and size must be between 1 and " + AnnouncementRules.MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String code, String message) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
