
package com.gamagerecruiters.marketplace.modules.payment.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.ProviderEarningsState;

/**
 * Provides persistence access to provider earnings balance snapshots.
 *
 * <p>
 * Each provider/currency combination has one deterministic document ID.
 * Financial state changes must be performed through the controlled
 * earnings ledger service within a MongoDB transaction.
 * </p>
 *
 * <p>
 * This repository must not be used to update financial balances
 * independently of the corresponding ledger event.
 * </p>
 */
public interface ProviderEarningsStateRepository
        extends MongoRepository<ProviderEarningsState, String> {
}
