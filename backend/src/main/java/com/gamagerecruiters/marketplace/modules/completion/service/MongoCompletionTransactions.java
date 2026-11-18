package com.gamagerecruiters.marketplace.modules.completion.service;

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
 * MongoDB multi-document transaction for completion operations.
 *
 * <p>Uses the team's shared {@link MongoTransactionManager} bean (MongoTransactionConfig), so the
 * completion module follows the same transaction strategy as the other modules. Repository and
 * MongoTemplate calls made inside {@link #inTransaction} — including BookingFacade's booking
 * save — join the transaction automatically. Requires a replica set (MongoDB Atlas,
 * Testcontainers MongoDBContainer).
 *
 * <p>The manager is looked up on first use: the "test" profile (MVC slice tests with MongoDB
 * disabled) has no transaction manager and never runs completion writes.
 */
@Component
public class MongoCompletionTransactions implements CompletionTransactions {

    static final String UPDATE_CONFLICT = "COMPLETION_UPDATE_CONFLICT";

    /** MongoDB "WriteConflict": another transaction changed the same document. */
    private static final int WRITE_CONFLICT_CODE = 112;
    private static final String TRANSIENT_TRANSACTION_ERROR = "TransientTransactionError";

    private final ObjectProvider<MongoTransactionManager> transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    public MongoCompletionTransactions(ObjectProvider<MongoTransactionManager> transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
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

    /** True when the failure was caused by a concurrent change of the same booking or completion. */
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
                "This booking was changed by another request at the same time. Please reload it and try again");
    }
}