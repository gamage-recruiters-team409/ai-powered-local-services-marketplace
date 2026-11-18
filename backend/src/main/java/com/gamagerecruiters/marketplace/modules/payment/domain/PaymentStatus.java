package com.gamagerecruiters.marketplace.modules.payment.domain;

public enum PaymentStatus {
    INITIATED,
    AUTHORIZED,
    CAPTURED,
    RELEASED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED
}
