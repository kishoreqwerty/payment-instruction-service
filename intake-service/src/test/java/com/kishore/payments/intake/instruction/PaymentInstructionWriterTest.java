package com.kishore.payments.intake.instruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.intake.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The phase's core claim: instruction, event and outbox row are one
 * transaction. Without {@link #failureBetweenEventAndOutboxRollsBackAllThree()}
 * in particular, the outbox is decoration -- a row that only sometimes exists
 * alongside the state it describes is worse than no outbox at all, since it
 * would be silently trusted.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class PaymentInstructionWriterTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentInstructionWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @SpyBean
    private OutboxWriter outboxWriter;

    @Test
    void commitPathWritesOneInstructionOneEventOneOutboxRow() {
        PaymentInstructionEntity entity = sampleEntity();

        writer.insertNew(entity);

        assertThat(countWhere("core.payment_instruction", "instruction_id", entity.getInstructionId())).isEqualTo(1);
        assertThat(countWhere("core.instruction_event", "instruction_id", entity.getInstructionId())).isEqualTo(1);
        assertThat(countWhere("core.outbox", "aggregate_id", entity.getInstructionId())).isEqualTo(1);

        var outboxRow = jdbc.queryForMap(
                "SELECT topic, partition_key, published_at FROM core.outbox WHERE aggregate_id = ?::uuid",
                entity.getInstructionId());
        assertThat(outboxRow.get("topic")).isEqualTo("payments.received");
        assertThat(outboxRow.get("partition_key")).isEqualTo(entity.getInstructionId().toString());
        assertThat(outboxRow.get("published_at")).isNull();
    }

    @Test
    void rollbackLeavesAllThreeAbsent() {
        PaymentInstructionEntity entity = sampleEntity();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            writer.insertNew(entity);
            status.setRollbackOnly();
        });

        assertThat(countWhere("core.payment_instruction", "instruction_id", entity.getInstructionId())).isZero();
        assertThat(countWhere("core.instruction_event", "instruction_id", entity.getInstructionId())).isZero();
        assertThat(countWhere("core.outbox", "aggregate_id", entity.getInstructionId())).isZero();
    }

    @Test
    void failureBetweenEventAndOutboxRollsBackAllThree() {
        PaymentInstructionEntity entity = sampleEntity();
        doThrow(new RuntimeException("simulated failure writing the outbox row"))
                .when(outboxWriter)
                .write(any());

        assertThatThrownBy(() -> writer.insertNew(entity)).isInstanceOf(RuntimeException.class);

        // The instruction and event inserts ran (in-transaction) before the
        // simulated failure; this proves they didn't survive the rollback
        // that failure triggered, not just that the outbox write failed.
        assertThat(countWhere("core.payment_instruction", "instruction_id", entity.getInstructionId())).isZero();
        assertThat(countWhere("core.instruction_event", "instruction_id", entity.getInstructionId())).isZero();
        assertThat(countWhere("core.outbox", "aggregate_id", entity.getInstructionId())).isZero();
    }

    private Integer countWhere(String table, String column, UUID value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?::uuid", Integer.class, value);
    }

    private static PaymentInstructionEntity sampleEntity() {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID(),
                null,
                "Debtor",
                "ACC-" + UUID.randomUUID(),
                "DEUTDEFFXXX",
                "Creditor",
                "CRED-ACC",
                "CHASUS33XXX",
                new BigDecimal("100.00"),
                "USD",
                "SLEV",
                LocalDate.now().plusDays(1));
    }
}
