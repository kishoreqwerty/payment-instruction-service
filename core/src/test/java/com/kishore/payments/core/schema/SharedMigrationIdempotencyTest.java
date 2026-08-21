package com.kishore.payments.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduces, at the Flyway level rather than the full-service level, the
 * defect found running every service against one shared local database (see
 * .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md): four independently
 * deployed services each depend on `core` and each run its own Flyway
 * instance against the same physical database, so V1 (this schema) is
 * migrated redundantly, from a genuinely empty, independent history table
 * every time a service that isn't "first" starts against a blank database.
 * Before V1 was made idempotent, the second, third and fourth independent
 * history to attempt it failed outright ("schema core already exists").
 *
 * <p>{@link MigrationTest} already proves V1 produces the right schema from
 * a single Flyway run; this test's only job is the property that changed --
 * that a second, wholly independent Flyway history can migrate the same V1
 * against a database where its objects already exist, and come out clean.
 */
@Testcontainers
class SharedMigrationIdempotencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void v1MigratesCleanlyFromASecondIndependentHistoryAfterTheFirstAlreadyCreatedTheSchema() throws SQLException {
        Flyway firstService = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("service_a")
                .table("flyway_schema_history")
                .load();
        firstService.migrate();

        // A second service, starting independently against the same database: its own history table
        // lives in its own schema and has never seen V1 before, but core.* and intake.* already exist
        // physically because the first service just created them. This must not fail.
        Flyway secondService = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("service_b")
                .table("flyway_schema_history")
                .load();

        assertThatCode(secondService::migrate).doesNotThrowAnyException();

        // Not just "didn't throw" -- the underlying objects are exactly as V1 defines them, not
        // duplicated or partially overwritten by the second, redundant application attempt.
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM pg_type WHERE typname = 'instruction_state' AND typnamespace = 'core'::regnamespace")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).as("exactly one core.instruction_state type, not duplicated").isEqualTo(1);
        }

        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'core' AND table_name = 'payment_instruction'")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).as("exactly one core.payment_instruction table").isEqualTo(1);
        }
    }
}
