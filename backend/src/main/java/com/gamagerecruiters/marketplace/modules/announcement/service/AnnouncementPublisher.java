package com.gamagerecruiters.marketplace.modules.announcement.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;

/**
 * Publishes SCHEDULED announcements whose scheduledAt has passed. Each one is claimed and published in ONE atomic
 * findAndModify on status = SCHEDULED, so it is published exactly once even with several instances running; an edit
 * or delete racing with it gets 409 (conditional on status). Each published announcement is delivered once (stub).
 *
 * <p>Settings (defaults in brackets): {@code app.announcement.publisher.enabled} [true], {@code .fixed-delay} [PT1M],
 * {@code .initial-delay} [PT30S], {@code .batch-size} [50]. Only ids are logged.</p>
 */
@Component
public class AnnouncementPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementPublisher.class);

    private final AnnouncementStore store;
    private final AnnouncementService announcementService;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration pinDuration;

    @Autowired
    public AnnouncementPublisher(AnnouncementStore store,
                                 AnnouncementService announcementService,
                                 @Value("${app.announcement.publisher.enabled:true}") boolean enabled,
                                 @Value("${app.announcement.publisher.batch-size:50}") int batchSize,
                                 @Value("${app.announcement.pin-duration:PT48H}") Duration pinDuration) {
        this(store, announcementService, Clock.systemUTC(), enabled, batchSize, pinDuration);
    }

    AnnouncementPublisher(AnnouncementStore store, AnnouncementService announcementService, Clock clock,
                          boolean enabled, int batchSize, Duration pinDuration) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.announcement.publisher.batch-size must be at least 1");
        }
        this.store = store;
        this.announcementService = announcementService;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.pinDuration = pinDuration;
    }

    /** Runs only when scheduling is enabled (AnnouncementSchedulingConfig) and the publisher is not switched off. */
    @Scheduled(fixedDelayString = "${app.announcement.publisher.fixed-delay:PT1M}",
            initialDelayString = "${app.announcement.publisher.initial-delay:PT30S}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            int published = publishDue();
            if (published > 0) {
                log.info("event=ANNOUNCEMENT_PUBLISHER_RUN published={}", published);
            }
        } catch (RuntimeException e) {
            log.error("event=ANNOUNCEMENT_PUBLISHER_FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    /** Publishes up to {@code batch-size} due announcements. Returns how many this call published. */
    public int publishDue() {
        int published = 0;
        while (published < batchSize) {
            Instant now = clock.instant();
            Optional<Announcement> claimed = store.claimDue(now, true, now.plus(pinDuration))
                    .or(() -> store.claimDue(now, false, null));
            if (claimed.isEmpty()) {
                break;
            }
            published++;
            log.info("event=ANNOUNCEMENT_PUBLISHED announcementId={} scheduled=true", claimed.get().getId());
            announcementService.deliver(claimed.get());
        }
        return published;
    }
}
