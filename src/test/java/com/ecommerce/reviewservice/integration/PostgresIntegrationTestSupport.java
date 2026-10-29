package com.ecommerce.reviewservice.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for the *IT classes: one {@code postgres:16-alpine} container (the
 * image production runs) shared by every integration test class in the JVM.
 *
 * <p>The container is started once in the static initialiser and never stopped
 * explicitly (Testcontainers' Ryuk reaps it when the JVM exits). Because every
 * subclass sees the same {@code @ServiceConnection} field, Spring's test context
 * cache key is identical across classes with the same configuration, so contexts
 * are reused too.
 *
 * <p>Schema comes from the real Flyway migrations in {@code db/migration} and
 * Hibernate runs with {@code ddl-auto=validate} (application.yaml) — any drift
 * between the entities and the migrations fails context start-up here, which the
 * H2 {@code test} profile (schema generated from the entities) cannot catch.
 */
public abstract class PostgresIntegrationTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
