package com.gamagerecruiters.marketplace.modules.announcement.domain;

/**
 * Whether the announcement was delivered (push / email / in-app banner). Only NOT_APPLIED exists for now: delivery
 * is a stub until the notification module offers real channels. A delivered state is never faked.
 */
public enum DeliveryState {
    /** Nothing was delivered (no real delivery integration yet). */
    NOT_APPLIED
}
