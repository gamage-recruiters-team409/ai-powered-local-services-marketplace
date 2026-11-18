package com.gamagerecruiters.marketplace.modules.completion.service;

import java.util.function.Supplier;

/**
 * Runs one completion operation as a single unit of work.
 *
 * <p>The completion evidence (service_completions) and the booking status change (through
 * BookingFacade) are written in the same MongoDB transaction: either both are committed or
 * neither is. A write conflict with a concurrent request is reported as 409 instead of
 * overwriting the other request's result.
 */
public interface CompletionTransactions {

    /** Runs {@code work} in one transaction and returns its result; any exception rolls everything back. */
    <T> T inTransaction(Supplier<T> work);

    /** Runs the work directly, without a transaction (unit tests only). */
    static CompletionTransactions direct() {
        return new CompletionTransactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
