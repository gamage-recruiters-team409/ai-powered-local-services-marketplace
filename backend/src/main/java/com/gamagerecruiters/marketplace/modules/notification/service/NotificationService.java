package com.gamagerecruiters.marketplace.modules.notification.service;

/**
 * Best-effort notification boundary: never throws, never rolls back or fails the caller, skips
 * non-ACTIVE recipients, and accepts one event per recipient. Data must be display values only:
 * never tokens, links, passwords, addresses; before booking confirmation, only city or district
 * may identify a location.
 */
public interface NotificationService {
    void notify(NotificationEvent event);
}
