package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * What a settlement port reports. A port that fails, or cannot tell whether its change was applied, THROWS; the
 * caller then records FAILED and retries later with the same idempotency key. A permanent rejection of the finance
 * step is {@link SettlementRejectedException}.
 */
public enum SettlementOutcome {
    /** The other module confirmed the change. */
    APPLIED,
    /** Nothing was applied because no real integration exists yet (stub). Never reported as success. */
    NOT_APPLIED
}
