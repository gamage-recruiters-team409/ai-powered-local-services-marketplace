package com.gamagerecruiters.marketplace.modules.completion.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;

/**
 * Read-only access to completion data for other modules (for example reviews),
 * so they do not use the completion repository directly.
 */
@Service
public class CompletionLookup {

    private final ServiceCompletionRepository completionRepository;

    public CompletionLookup(ServiceCompletionRepository completionRepository) {
        this.completionRepository = completionRepository;
    }

    /** When the service was confirmed (by the customer or the 48-hour auto-confirm), if it was. */
    public Optional<Instant> findConfirmedAt(String bookingId) {
        return completionRepository.findByBookingId(bookingId).map(ServiceCompletion::getConfirmedAt);
    }
}
