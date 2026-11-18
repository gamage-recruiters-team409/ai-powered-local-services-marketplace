package com.gamagerecruiters.marketplace.modules.completion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpStatus;

import com.mongodb.MongoException;

/** Which failures are reported as 409 COMPLETION_UPDATE_CONFLICT (the real transactions run in the integration test). */
class MongoCompletionTransactionsTest {

    @Test
    @DisplayName("MongoDB WriteConflict (code 112) inside a Spring exception is a concurrent update")
    void writeConflictIsConcurrentUpdate() {
        MongoException writeConflict = new MongoException(112, "WriteConflict");

        assertTrue(MongoCompletionTransactions.isConcurrentUpdate(
                new UncategorizedMongoDbException("write failed", writeConflict)));
    }

    @Test
    @DisplayName("TransientTransactionError label is a concurrent update")
    void transientTransactionErrorIsConcurrentUpdate() {
        MongoException aborted = new MongoException(251, "NoSuchTransaction");
        aborted.addLabel("TransientTransactionError");

        assertTrue(MongoCompletionTransactions.isConcurrentUpdate(aborted));
    }

    @Test
    @DisplayName("Optimistic locking failure (booking @Version) is a concurrent update")
    void optimisticLockingIsConcurrentUpdate() {
        assertTrue(MongoCompletionTransactions.isConcurrentUpdate(new OptimisticLockingFailureException("version changed")));
    }

    @Test
    @DisplayName("Other database errors are not hidden as conflicts")
    void otherErrorsAreNotConflicts() {
        assertFalse(MongoCompletionTransactions.isConcurrentUpdate(
                new DataIntegrityViolationException("bad data", new MongoException(2, "BadValue"))));
        assertFalse(MongoCompletionTransactions.isConcurrentUpdate(new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("The conflict response is 409 COMPLETION_UPDATE_CONFLICT")
    void conflictResponse() {
        assertEquals(HttpStatus.CONFLICT, MongoCompletionTransactions.updateConflict().getStatus());
        assertEquals("COMPLETION_UPDATE_CONFLICT", MongoCompletionTransactions.updateConflict().getErrorCode());
    }
}
