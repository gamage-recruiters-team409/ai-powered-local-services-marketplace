
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.ProviderEarningsState;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.ProviderEarningsStateRepository;

/**
 * Performs atomic earnings ledger and balance-state persistence.
 *
 * <p>
 * Each successful transaction updates one provider/currency balance
 * snapshot and inserts exactly one immutable financial ledger event.
 * </p>
 *
 * <p>
 * Transaction retries and idempotency resolution are handled by the
 * calling EarningsLedgerService, outside this transaction boundary.
 * </p>
 */
@Service
public class EarningsLedgerTransactionService {

    private final EarningsLedgerRepository ledgerRepository;
    private final ProviderEarningsStateRepository stateRepository;
    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;

    public EarningsLedgerTransactionService(
            EarningsLedgerRepository ledgerRepository,
            ProviderEarningsStateRepository stateRepository,
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager) {

        this.ledgerRepository = Objects.requireNonNull(ledgerRepository);
        this.stateRepository = Objects.requireNonNull(stateRepository);
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate);
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    /**
     * Validates and appends an earnings event within a MongoDB transaction.
     *
     * @param entry new, validated event without an assigned sequence
     * @return the committed ledger event
     * @throws IllegalStateException if the transition is financially
     *                               invalid or legacy data needs migration
     */
    public EarningsLedgerEntry appendAtomically(EarningsLedgerEntry entry) {
        Objects.requireNonNull(entry, "Ledger entry is required");

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            // Recheck idempotency inside the transaction.
            if (ledgerRepository.existsByEventKey(entry.getEventKey())) {
                throw new IllegalStateException(
                        "Ledger event key already exists; resolve idempotency outside transaction");
            }

            ProviderEarningsState state = loadOrInitializeState(entry);

            // Validate financial balances before persisting any changes.
            state.applyEvent(entry);

            long nextSequence = Math.addExact(
                    state.getLastSequenceNumber(), 1L);

            state.setLastSequenceNumber(nextSequence);
            entry.setSequenceNumber(nextSequence);

            // Updating the shared state document creates a write conflict
            // when concurrent transactions modify the same balance.
            stateRepository.save(state);

            // insert() preserves the append-only ledger write contract.
            return mongoTemplate.insert(entry);
        }));
    }

    /**
     * Loads the provider/currency state or initializes an empty ledger.
     *
     * <p>
     * Existing ledger events without a corresponding state require an
     * explicit verified migration. They must not be treated as zero balance.
     * </p>
     */
    private ProviderEarningsState loadOrInitializeState(
            EarningsLedgerEntry entry) {

        ProviderEarningsState initial = new ProviderEarningsState(
                entry.getProviderId(),
                entry.getCurrency());

        return stateRepository.findById(initial.getId())
                .orElseGet(() -> {
                    if (ledgerRepository.existsByProviderIdAndCurrency(
                            entry.getProviderId(),
                            entry.getCurrency())) {
                        throw new IllegalStateException(
                                "Existing earnings ledger requires state migration "
                                        + "before new financial events can be appended");
                    }

                    return initial;
                });
    }
}
