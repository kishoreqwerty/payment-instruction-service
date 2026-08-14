package com.kishore.payments.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        // Migration completes with no errors.
        flyway.migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void instructionStateEnumHas13LabelsInDeclaredOrder() throws SQLException {
        List<String> expected = List.of(
                "RECEIVED", "VALIDATED", "ENRICHED", "ROUTED", "SENT", "SENT_UNCONFIRMED",
                "SETTLED", "RETURNED", "EXCEPTION", "REPAIRED", "INVESTIGATION", "REJECTED", "CANCELLED");

        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT enumlabel FROM pg_enum "
                                + "JOIN pg_type ON pg_enum.enumtypid = pg_type.oid "
                                + "JOIN pg_namespace ON pg_type.typnamespace = pg_namespace.oid "
                                + "WHERE pg_namespace.nspname = 'core' AND pg_type.typname = 'instruction_state' "
                                + "ORDER BY enumsortorder")) {
            ResultSet rs = ps.executeQuery();
            List<String> actual = new ArrayList<>();
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(actual).hasSize(13);
        }
    }

    @Test
    void uqIdempotencyExistsAndIsUnique() throws SQLException {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE schemaname = 'core' AND tablename = 'payment_instruction' "
                                + "AND indexname = 'uq_idempotency'")) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as("uq_idempotency index should exist").isTrue();
            assertThat(rs.getString(1)).containsIgnoringCase("UNIQUE");
        }
    }

    @Test
    void uqInstructionEventAuditExistsAndIsUnique() throws SQLException {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE schemaname = 'core' AND tablename = 'instruction_event' "
                                + "AND indexname = 'uq_instruction_event_audit'")) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as("uq_instruction_event_audit index should exist").isTrue();
            assertThat(rs.getString(1))
                    .containsIgnoringCase("UNIQUE")
                    .contains("instruction_id", "sequence_no");
        }
    }

    @Test
    void duplicateIdempotencyKeyRaisesConstraintViolation() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(true);
            insertInstruction(conn, UUID.randomUUID(), "E2E-DUP", "ACC-DUP", new java.math.BigDecimal("10.00"));

            assertThatThrownBy(() ->
                    insertInstruction(conn, UUID.randomUUID(), "E2E-DUP", "ACC-DUP", new java.math.BigDecimal("10.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_idempotency");
        }
    }

    @Test
    void collidingAuditConstraintRaisesConstraintViolation() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(true);
            UUID instructionId = UUID.randomUUID();
            insertInstruction(conn, instructionId, "E2E-AUDIT", "ACC-AUDIT", new java.math.BigDecimal("25.00"));

            insertEvent(conn, instructionId, 1, Timestamp.from(Instant.now()));

            assertThatThrownBy(() -> insertEvent(conn, instructionId, 1, Timestamp.from(Instant.now())))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_instruction_event_audit");
        }
    }

    /**
     * The audit constraint is (instruction_id, sequence_no) only -- occurred_at
     * is not part of it. A redelivered event with a different timestamp must
     * still be rejected; this is the case that a partitioned instruction_event
     * table (occurred_at forced into the constraint) would have let through.
     * See the "partitioning removed" note in
     * .notes/reports/PHASE-1-REPORT.md.
     */
    @Test
    void sameSequenceNoWithDifferentOccurredAtRaisesConstraintViolation() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(true);
            UUID instructionId = UUID.randomUUID();
            insertInstruction(conn, instructionId, "E2E-GAP", "ACC-GAP", new java.math.BigDecimal("25.00"));

            insertEvent(conn, instructionId, 1, Timestamp.from(Instant.now()));

            assertThatThrownBy(() ->
                    insertEvent(conn, instructionId, 1, Timestamp.from(Instant.now().plusSeconds(86_400))))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_instruction_event_audit");
        }
    }

    @Test
    void zeroAmountRaisesCheckViolation() throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(true);
            assertThatThrownBy(() ->
                    insertInstruction(conn, UUID.randomUUID(), "E2E-ZERO", "ACC-ZERO", java.math.BigDecimal.ZERO))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("payment_instruction_amount_check");
        }
    }

    private static void insertInstruction(
            Connection conn, UUID instructionId, String endToEndId, String debtorAccount, java.math.BigDecimal amount)
            throws SQLException {
        String sql = "INSERT INTO core.payment_instruction ("
                + "instruction_id, raw_message_id, uetr, end_to_end_id, state, "
                + "debtor_name, debtor_account, debtor_agent_bic, "
                + "creditor_name, creditor_account, creditor_agent_bic, "
                + "amount, currency, requested_exec_date"
                + ") VALUES (?, ?, ?, ?, 'RECEIVED'::core.instruction_state, "
                + "'Debtor', ?, 'DEUTDEFFXXX', 'Creditor', 'CRED-ACC', 'CHASUS33XXX', ?, 'USD', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, instructionId);
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, endToEndId);
            ps.setString(5, debtorAccount);
            ps.setBigDecimal(6, amount);
            ps.setObject(7, LocalDate.now());
            ps.executeUpdate();
        }
    }

    private static void insertEvent(Connection conn, UUID instructionId, int sequenceNo, Timestamp occurredAt)
            throws SQLException {
        String sql = "INSERT INTO core.instruction_event ("
                + "instruction_id, sequence_no, from_state, to_state, occurred_at, actor_type, actor_id"
                + ") VALUES (?, ?, NULL, 'VALIDATED'::core.instruction_state, ?, 'SYSTEM', 'test')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, instructionId);
            ps.setInt(2, sequenceNo);
            ps.setTimestamp(3, occurredAt);
            ps.executeUpdate();
        }
    }
}
