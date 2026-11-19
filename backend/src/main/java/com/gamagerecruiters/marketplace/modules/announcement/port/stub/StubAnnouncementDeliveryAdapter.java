package com.gamagerecruiters.marketplace.modules.announcement.port.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.announcement.domain.DeliveryState;
import com.gamagerecruiters.marketplace.modules.announcement.port.AnnouncementDeliveryPort;

/**
 * STUB. NOTHING IS DELIVERED: no push, no email, no in-app banner. This class only writes a log line (id, audience
 * and flags; never the title or message) and reports NOT_APPLIED, never a delivered state.
 *
 * <p>Deferred until the notification module offers push, email and in-app banner channels. That module is not
 * touched from here.</p>
 * TODO(notification): replace with an adapter over the notification module, then delete this class.
 */
@Component
public class StubAnnouncementDeliveryAdapter implements AnnouncementDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(StubAnnouncementDeliveryAdapter.class);

    @Override
    public DeliveryState deliver(Delivery delivery) {
        log.warn("event=ANNOUNCEMENT_DELIVERY_NOT_APPLIED reason=NOTIFICATION_NOT_INTEGRATED announcementId={} "
                        + "audience={} sendPush={} pinToHeader={}",
                delivery.announcementId(), delivery.audience(), delivery.sendPush(), delivery.pinToHeader());
        return DeliveryState.NOT_APPLIED;
    }
}
