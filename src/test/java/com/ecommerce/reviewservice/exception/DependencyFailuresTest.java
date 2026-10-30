package com.ecommerce.reviewservice.exception;

import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyFailuresTest {

    @Test
    void infrastructureExceptions_areUnavailable() {
        assertThat(DependencyFailures.unavailableCause(new CannotGetJdbcConnectionException("pool", new SQLException("x")))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(new CannotCreateTransactionException("begin"))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(new DataAccessResourceFailureException("down"))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(new QueryTimeoutException("slow"))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(new CannotAcquireLockException("lock timeout"))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(new DeadlockLoserDataAccessException("deadlock", null))).isNotNull();
        assertThat(DependencyFailures.unavailableCause(
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms"))).isNotNull();
    }

    @Test
    void hibernateAndJpaFailures_areUnavailable_evenWhenWrapped() {
        Throwable lockTimeout = new JpaSystemException(new RuntimeException("flush failed",
                new LockTimeoutException("could not obtain lock", new SQLException("lock timeout", "55P03"), "select")));
        Throwable connection = new RuntimeException("outer", new JDBCConnectionException("gone", new SQLException("boom", "08006")));

        assertThat(DependencyFailures.unavailableCause(lockTimeout)).isInstanceOf(LockTimeoutException.class);
        assertThat(DependencyFailures.unavailableCause(connection)).isInstanceOf(JDBCConnectionException.class);
    }

    @ParameterizedTest(name = "SQLSTATE {0} is a dependency failure")
    @ValueSource(strings = {"08001", "08006", "53300", "57P01", "57P03", "55P03", "40001", "40P01"})
    void sqlStates_thatMeanUnavailable(String state) {
        Throwable wrapped = new IllegalStateException("wrapper", new SQLException("pg", state));

        assertThat(DependencyFailures.unavailableCause(wrapped)).isInstanceOf(SQLException.class);
    }

    /** What pgjdbc throws when {@code socketTimeout} expires on a database that stopped answering. */
    @Test
    void aSocketReadTimeoutOnAFrozenDatabase_isUnavailable() {
        Throwable frozen = new IllegalStateException("wrapper", new PSQLException(
                "An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE, new SocketTimeoutException("Read timed out")));

        assertThat(DependencyFailures.unavailableCause(frozen)).isInstanceOf(PSQLException.class);
    }

    @ParameterizedTest(name = "SQLSTATE {0} is not a dependency failure")
    @ValueSource(strings = {"23505", "23503", "22001", "42P01", "42703"})
    void sqlStates_thatAreOurOwnBugsOrClientErrors(String state) {
        assertThat(DependencyFailures.unavailableCause(new SQLException("pg", state))).isNull();
    }

    @Test
    void uniqueViolationRace_staysA409NotA503() {
        Throwable race = new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));

        assertThat(DependencyFailures.unavailableCause(race)).isNull();
    }

    @Test
    void aRollbackThatFailsBecauseTheConnectionDied_isAnOutage_notAnUnexpectedError() {
        // what a killed backend or a Postgres restart looks like: the rollback's exception overrides the original one
        Throwable rollbackFailed = new org.springframework.transaction.TransactionSystemException("Could not roll back JPA transaction",
                new org.hibernate.TransactionException("Unable to rollback against JDBC Connection",
                        new SQLException("This connection has been closed.", (String) null)));

        assertThat(DependencyFailures.unavailableCause(rollbackFailed)).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
    }

    @Test
    void aCommitThatFailsOnTheData_isNotAnOutage() {
        Throwable constraint = new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction",
                new org.hibernate.TransactionException("Unable to commit against JDBC Connection",
                        new SQLException("duplicate key value violates unique constraint", "23505")));
        Throwable noSqlBehindIt = new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction",
                new IllegalStateException("bug"));

        assertThat(DependencyFailures.unavailableCause(constraint)).isNull();
        assertThat(DependencyFailures.unavailableCause(noSqlBehindIt)).isNull();
    }

    @Test
    void aLostOptimisticLockRace_isAConflict_notAnOutage() {
        assertThat(DependencyFailures.unavailableCause(new org.springframework.dao.OptimisticLockingFailureException("version moved"))).isNull();
    }

    @Test
    void ordinaryBugs_areNotDependencyFailures() {
        assertThat(DependencyFailures.unavailableCause(new IllegalStateException("bug"))).isNull();
        assertThat(DependencyFailures.unavailableCause(new NullPointerException())).isNull();
    }

    @Test
    void aCauseCycle_doesNotLoopForever() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(DependencyFailures.unavailableCause(a)).isNull();
    }
}
