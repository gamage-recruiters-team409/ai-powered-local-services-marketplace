
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Provides controlled persistence and retrieval of provider earnings events.
 *
 * <p>
 * Ledger records are inserted as new documents. Existing records are
 * never intentionally updated or deleted by this service.
 * </p>
 *
 * <p>
 * This service does not authorize financial events. Callers must first
 * verify the originating payment, booking and business operation.
 * </p>
 */
@Service
public class EarningsLedgerService {

    private final EarningsLedgerRepository repository;
    private final MongoTemplate mongoTemplate;

    public EarningsLedgerService(
            EarningsLedgerRepository repository,
            MongoTemplate mongoTemplate) {
        this.repository = Objects.requireNonNull(repository);
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
    }

    /**
     * Inserts a new financial event or returns an identical existing event.
     *
     * <p>
     * The unique eventKey index is the final concurrency safeguard.
     * A preliminary lookup alone cannot prevent simultaneous inserts.
     * </p>
     *
     * @param entry new ledger event without a persisted document ID
     * @return the newly inserted or previously recorded event
     * @throws IllegalArgumentException if the event is invalid
     * @throws IllegalStateException    if the event key conflicts with
     *                                  different financial event data
     */
    public EarningsLedgerEntry appendEvent(EarningsLedgerEntry entry) {
        Objects.requireNonNull(entry, "Ledger entry is required");

        entry.validate();

        // Prevent callers from using this operation to overwrite an entry.
        if (entry.getId() != null) {
            throw new IllegalArgumentException(
                    "New ledger entries must not contain a document ID");
        }

        var existing = repository.findByEventKey(entry.getEventKey());

        if (existing.isPresent()) {
            return requireMatchingEvent(existing.get(), entry);
        }

        try {
            // insert(), unlike save(), does not intentionally replace records.
            return mongoTemplate.insert(entry);

        } catch (DuplicateKeyException exception) {
            // A concurrent request may have inserted the same event first.
            // Only an identical existing event is treated as idempotent.
            return repository.findByEventKey(entry.getEventKey())
                    .map(recorded -> requireMatchingEvent(recorded, entry))
                    .orElseThrow(() -> exception);
        }
    }

    /**
     * Returns the provider's ledger history in newest-first order.
     *
     * @param providerId verified provider identifier
     * @return matching financial events
     */
    public List<EarningsLedgerEntry> getProviderHistory(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider ID is required");
        }

        return repository.findByProviderIdOrderByCreatedAtDesc(providerId);
    }

    /**
     * Confirms that a repeated event key refers to the same event.
     *
     * <p>
     * Creation timestamps are not compared because a retry may
     * construct the same event at a different time. The original
     * persisted timestamp is preserved.
     * </p>
     */
    private EarningsLedgerEntry requireMatchingEvent(
            EarningsLedgerEntry recorded,
            EarningsLedgerEntry requested) {

        boolean matches = Objects.equals(recorded.getEventKey(), requested.getEventKey())
                && Objects.equals(recorded.getPaymentId(), requested.getPaymentId())
                && Objects.equals(recorded.getBookingId(), requested.getBookingId())
                && Objects.equals(recorded.getProviderId(), requested.getProviderId())
                && recorded.getEventType() == requested.getEventType()
                && recorded.getAmount() != null
                && requested.getAmount() != null
                && recorded.getAmount().compareTo(requested.getAmount()) == 0
                && Objects.equals(recorded.getCurrency(), requested.getCurrency());

        if (!matches) {
            throw new IllegalStateException(
                    "Ledger event key already exists with different financial data");
        }

        return recorded;
    }
}
