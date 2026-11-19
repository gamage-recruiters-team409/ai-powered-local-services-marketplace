package com.gamagerecruiters.marketplace.modules.announcement.port;

import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementAudience;
import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;

/**
 * Delivers a published announcement to its audience: push (when {@code sendPush}), email and the in-app banner
 * (when {@code pinToHeader}). Called once when an announcement becomes PUBLISHED; never again for later text edits.
 *
 * <p>No notification module API exists yet, so the active adapter is a stub that delivers nothing and reports
 * NOT_APPLIED. A real adapter must report a delivered state only when the notification module confirms it.</p>
 */
public interface AnnouncementDeliveryPort {

    /** What to deliver. Ids and flags only: the content is read by the delivering module itself. */
    record Delivery(String announcementId, AnnouncementAudience audience, boolean sendPush, boolean pinToHeader) {
    }

    /** @throws RuntimeException if delivery failed; the announcement stays published either way */
    DeliveryState deliver(Delivery delivery);
}
