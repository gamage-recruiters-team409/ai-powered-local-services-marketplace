package com.gamagerecruiters.marketplace.modules.notification.service;

/**
 * Best-effort notification boundary: never throws, never rolls back or fails the caller, skips
 * non-ACTIVE recipients, and accepts one event per recipient. Data must be display values only:
 * never tokens, links, passwords, addresses; before booking confirmation, only city or district
 * may identify a location.
 * NEW_MESSAGE deduplication is race-free only within one backend instance because its single-thread
 * save executor serialises the check and save. Multi-instance duplicate unread messages are harmless;
 * a database unique partial index and upsert are deferred until multi-instance deployment is in scope.
 */
public interface NotificationService {
    void notify(NotificationEvent event);
}
