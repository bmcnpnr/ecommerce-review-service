package com.ecommerce.reviewservice.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Tells "the database did not answer" (no connection, pool exhausted, lock or query timeout, deadlock,
 * server shutting down) apart from a bug. The first is an operating condition: the handler answers 503 and
 * logs one throttled line without a stack trace. The second keeps the 500 and the (throttled) stack trace.
 *
 * <p>The whole cause chain is walked because Hibernate, Spring's translation and the transaction manager
 * wrap each other depending on where the failure surfaces (query, flush, commit, transaction begin).
 * Unique-constraint violations (SQLSTATE 23505) are deliberately not in here: those stay 409.
 */
final class DependencyFailures {

    private static final int MAX_DEPTH = 16;

    private DependencyFailures() {
    }

    /** @return the first throwable in the cause chain that means "dependency unavailable", or {@code null} */
    static Throwable unavailableCause(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++) {
            // a lost optimistic-locking race is a conflict even though Spring files it under "transient"
            if (t instanceof OptimisticLockingFailureException) {
                return null;
            }
            if (isUnavailable(t)) {
                return t;
            }
            Throwable next = t.getCause();
            t = next == t ? null : next;
        }
        return null;
    }

    private static boolean isUnavailable(Throwable t) {
        if (t instanceof CannotGetJdbcConnectionException
                || t instanceof CannotCreateTransactionException
                || t instanceof DataAccessResourceFailureException
                || t instanceof RecoverableDataAccessException
                || t instanceof TransientDataAccessException        // query timeout, lock timeout, deadlock, ...
                || t instanceof SQLTransientException               // Hikari "request timed out", SQL timeouts
                || t instanceof SQLRecoverableException
                || t instanceof org.hibernate.exception.JDBCConnectionException
                || t instanceof org.hibernate.exception.LockTimeoutException
                || t instanceof org.hibernate.exception.LockAcquisitionException
                || t instanceof org.hibernate.PessimisticLockException
                || t instanceof jakarta.persistence.PessimisticLockException
                || t instanceof jakarta.persistence.LockTimeoutException
                || t instanceof jakarta.persistence.QueryTimeoutException) {
            return true;
        }
        if (t instanceof org.hibernate.TransactionException || t instanceof org.springframework.transaction.TransactionSystemException) {
            // A commit or rollback that fails because the connection died mid-transaction (a killed backend, a database
            // restart): the rollback's exception overrides the original one, so without this it reached the client as
            // a 500 with a stack trace of several hundred lines. A failure to commit because of the data (a constraint,
            // a bad value) is not an outage and stays what it was.
            return causedByConnectionLevelSqlFailure(t);
        }
        return t instanceof SQLException sql && isUnavailableState(sql.getSQLState());
    }

    private static boolean causedByConnectionLevelSqlFailure(Throwable t) {
        Throwable cause = t.getCause();
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                return state == null || !(state.startsWith("22") || state.startsWith("23") || state.startsWith("42"));
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    /** 08 connection exception, 53 insufficient resources, 57P0x operator intervention, 55P03 lock timeout, 40xxx rollback. */
    private static boolean isUnavailableState(String state) {
        return state != null
                && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57P0")
                    || state.equals("55P03") || state.startsWith("40"));
    }
}
