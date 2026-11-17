
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.mongodb.MongoException;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Provides controlled persistence and retrieval of provider earnings events.
 *
 * <p>
 * Financial events are appended to the ledger and never intentionally
 * updated or deleted. Each new event is persisted atomically with
 * its provider's earnings balance state.
 * </p>
 *
 * <p>
 * This service enforces event idempotency and coordinates transaction
 * retries. It does not authorize the originating payment, booking,
 * or business operation; callers must verify those requirements.
 * </p>
 */
@Service
public class EarningsLedgerService {

    private static final int MAX_APPEND_ATTEMPTS = 5;

    private final EarningsLedgerRepository repository;
    private final EarningsLedgerTransactionService transactionService;

    /**
     * Creates the ledger service with its persistence dependencies.
     *
     * @param repository         ledger repository for retrieval and idempotency
     * @param transactionService atomic financial event persistence service
     */
    public EarningsLedgerService(
            EarningsLedgerRepository repository,
            EarningsLedgerTransactionService transactionService) {

        this.repository = Objects.requireNonNull(
                repository, "Ledger repository is required");

        this.transactionService = Objects.requireNonNull(
                transactionService, "Transaction service is required");
    }

    /**
     * Appends a new financial event or returns an identical committed event.
     *
     * <p>
     * Every persistence attempt uses a fresh ledger entry instance.
     * This prevents generated document IDs and processing sequences
     * from a failed transaction from affecting subsequent attempts.
     * </p>
     *
     * <p>
     * Only recognized transient MongoDB transaction failures are
     * eligible for bounded retries. Financial validation failures
     * are not retried.
     * </p>
     *
     * @param entry new financial event without a document ID or sequence
     * @return newly committed or previously recorded ledger event
     * @throws IllegalArgumentException if the event is invalid
     * @throws IllegalStateException    if the event key conflicts with
     *                                  different financial data
     */
    public EarningsLedgerEntry appendEvent(EarningsLedgerEntry entry) {

        Objects.requireNonNull(entry, "Ledger entry is required");

        // Validate the event before performing database operations.
        entry.validate();

        // Clients must never assign persistence identifiers.
        if (entry.getId() != null) {
            throw new IllegalArgumentException(
                    "New ledger entries must not contain a document ID");
        }

        if (entry.getSequenceNumber() != null) {
            throw new IllegalArgumentException(
                    "New ledger entries must not contain a sequence number");
        }

        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {

            // Return an identical event that has already been committed.
            Optional<EarningsLedgerEntry> existing = repository.findByEventKey(entry.getEventKey());

            if (existing.isPresent()) {
                return requireMatchingEvent(existing.get(), entry);
            }

            try {
                // Use a new object for every transaction attempt.
                return transactionService.appendAtomically(
                        entry.copyForAppend());

            } catch (DuplicateKeyException exception) {

                // Another transaction may have committed the same event.
                Optional<EarningsLedgerEntry> recorded = repository.findByEventKey(entry.getEventKey());

                if (recorded.isPresent()) {
                    return requireMatchingEvent(recorded.get(), entry);
                }

                // Do not treat unrelated unique-index conflicts as
                // successful idempotent operations.
                throw exception;

            } catch (RuntimeException exception) {

                // Resolve a possibly committed event before retrying.
                // This is especially important when a transaction's
                // commit result is uncertain.
                if (isUncertainCommitResult(exception)) {
                    Optional<EarningsLedgerEntry> recorded = repository.findByEventKey(entry.getEventKey());

                    if (recorded.isPresent()) {
                        return requireMatchingEvent(recorded.get(), entry);
                    }

                    // An unknown commit result cannot safely be treated
                    // as a confirmed rollback.
                    throw exception;
                }

                if (!isTransientTransactionError(exception)
                        || attempt == MAX_APPEND_ATTEMPTS) {
                    throw exception;
                }

                // The previous transaction failed with a recognized
                // transient error. The next attempt reloads the latest
                // committed provider state and processing sequence.
            }
        }

        throw new IllegalStateException(
                "Earnings ledger append failed after retry attempts");
    }

    /**
     * Returns the provider's ledger history in newest-first order.
     *
     * <p>
     * This method preserves the existing repository retrieval behavior.
     * Financial balance calculations must use sequence numbers,
     * not creation timestamps, to determine event processing order.
     * </p>
     *
     * @param providerId verified provider identifier
     * @return matching financial ledger events
     */
    public List<EarningsLedgerEntry> getProviderHistory(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider ID is required");
        }

        return repository.findByProviderIdOrderByCreatedAtDesc(providerId);
    }

    /**
     * Checks whether an exception contains a MongoDB transient
     * transaction error label.
     *
     * @param exception database or transaction exception
     * @return true when a transient transaction error is identified
     */
    private boolean isTransientTransactionError(Throwable exception) {

        Throwable current = exception;

        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(
                            MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Checks whether MongoDB reported an unknown transaction
     * commit result.
     *
     * <p>
     * An uncertain commit must not automatically trigger a new
     * financial event because the original transaction may already
     * have committed successfully.
     * </p>
     *
     * @param exception transaction exception
     * @return true when commit outcome is uncertain
     */
    private boolean isUncertainCommitResult(Throwable exception) {

        Throwable current = exception;

        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(
                            MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Confirms that an existing event key represents the same
     * financial operation.
     *
     * <p>
     * Creation timestamps, document IDs, and processing sequences
     * are not compared because they are persistence metadata.
     * The original committed record is returned for valid retries.
     * </p>
     *
     * @param recorded  previously committed ledger event
     * @param requested incoming financial event
     * @return the original committed ledger event
     * @throws IllegalStateException if financial event data differs
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
