package com.gamagerecruiters.marketplace.modules.dispute.domain;

/**
 * Reason category chosen when raising a dispute.
 *
 * <p><b>PROPOSED values, NOT yet approved by the Team Lead.</b> Existing values are kept unchanged (WP08 maps its
 * issue types to SERVICE_NOT_DELIVERED, POOR_QUALITY, PROPERTY_DAMAGE, OTHER and NO_SHOW). The provider "Raise
 * dispute" screen offers PROPERTY_DAMAGE, CUSTOMER_BEHAVIOUR, NON_PAYMENT and OTHER ({@link #PROVIDER_RAISE}).</p>
 */
public enum DisputeCategory {
    SERVICE_NOT_DELIVERED,
    POOR_QUALITY,
    NO_SHOW,
    PRICING_OR_PAYMENT,
    PROPERTY_DAMAGE,
    CONDUCT,
    OTHER,
    /** Provider-raised: the customer's behaviour during the booking. */
    CUSTOMER_BEHAVIOUR,
    /** Provider-raised: the customer did not pay what was agreed. */
    NON_PAYMENT;

    /** Categories the provider "Raise dispute" screen offers (no-show is reported through WP08). */
    public static final java.util.Set<DisputeCategory> PROVIDER_RAISE =
            java.util.EnumSet.of(PROPERTY_DAMAGE, CUSTOMER_BEHAVIOUR, NON_PAYMENT, OTHER);
}
