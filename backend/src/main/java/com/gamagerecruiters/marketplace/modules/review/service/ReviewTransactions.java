package com.gamagerecruiters.marketplace.modules.review.service;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.mongodb.MongoException;

/**
 * Runs review moderation writes (report + counter, decision + report resolution) in one MongoDB transaction,
 * using the team's shared {@link MongoTransactionManager} (MongoTransactionConfig, PR #24) — the same approach
 * as the completion module. The manager is looked up on first use (the "test" profile has none).
 */
@Component
public class ReviewTransactions {

    static final String UPDATE_CONFLICT = "REVIEW_UPDATE_CONFLICT";

    private static final int WRITE_CONFLICT_CODE = 112;
    private static final String TRANSIENT_TRANSACTION_ERROR = "TransientTransactionError";

    private final ObjectProvider<MongoTransactionManager> transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    public ReviewTransactions(ObjectProvider<MongoTransactionManager> transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T> T inTransaction(Supplier<T> work) {
        try {
            return template().execute(status -> work.get());
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (isConcurrentUpdate(ex)) {
                throw updateConflict();
            }
            throw ex;
        }
    }

    private TransactionTemplate template() {
        TransactionTemplate template = transactionTemplate;
        if (template == null) {
            synchronized (this) {
                template = transactionTemplate;
                if (template == null) {
                    template = new TransactionTemplate(transactionManager.getObject());
                    transactionTemplate = template;
                }
            }
        }
        return template;
    }

    static boolean isConcurrentUpdate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException) {
                return true;
            }
            if (cause instanceof MongoException mongo
                    && (mongo.getCode() == WRITE_CONFLICT_CODE || mongo.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    static ResourceConflictException updateConflict() {
        return new ResourceConflictException(UPDATE_CONFLICT,
                "This review was changed by another request at the same time. Please reload it and try again");
    }
}
