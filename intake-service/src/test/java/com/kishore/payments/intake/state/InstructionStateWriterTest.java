package com.kishore.payments.intake.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.IllegalTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.intake.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class InstructionStateWriterTest extends AbstractIntegrationTest {

    @Autowired
    private InstructionStateWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentInstructionRepository instructions;

    @Autowired
    private InstructionEventRepository events;

    @Test
    void legalTransitionUpdatesStateBumpsVersionAndWritesEvent() {
        UUID id = seedInstruction(InstructionState.RECEIVED);

        TransitionResult result = writer.transition(id, InstructionState.VALIDATED, ActorType.SYSTEM, "test", null, null);

        assertThat(result.newState()).isEqualTo(InstructionState.VALIDATED);
        assertThat(result.sequenceNo()).isEqualTo(2);

        PaymentInstructionEntity reloaded = instructions.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(InstructionState.VALIDATED);
        assertThat(reloaded.getStateVersion()).isEqualTo(2);

        List<InstructionEventEntity> instructionEvents = eventsFor(id);
        assertThat(instructionEvents).hasSize(2);
        InstructionEventEntity last = instructionEvents.get(1);
        assertThat(last.getSequenceNo()).isEqualTo(2);
        assertThat(last.getFromState()).isEqualTo(InstructionState.RECEIVED);
        assertThat(last.getToState()).isEqualTo(InstructionState.VALIDATED);
        assertThat(last.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(last.getActorId()).isEqualTo("test");
    }

    @Test
    void illegalTransitionThrowsAndWritesNothing() {
        UUID id = seedInstruction(InstructionState.RECEIVED);

        assertThatThrownBy(() -> writer.transition(id, InstructionState.SETTLED, ActorType.SYSTEM, "test", null, null))
                .isInstanceOf(IllegalTransitionException.class);

        PaymentInstructionEntity reloaded = instructions.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(InstructionState.RECEIVED);
        assertThat(reloaded.getStateVersion()).isEqualTo(1);
        assertThat(eventsFor(id)).hasSize(1);
    }

    @RepeatedTest(3)
    void concurrentTransitionsProduceExactlyOneWinner() throws Exception {
        UUID id = seedInstruction(InstructionState.RECEIVED);
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    writer.transition(id, InstructionState.VALIDATED, ActorType.SYSTEM, "concurrent", null, null);
                    return true;
                } catch (ConcurrentTransitionException e) {
                    return false;
                }
            }));
        }

        ready.await();
        go.countDown();

        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).as("exactly one concurrent writer should win the optimistic lock").isEqualTo(1);

        PaymentInstructionEntity reloaded = instructions.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(InstructionState.VALIDATED);
        assertThat(reloaded.getStateVersion()).isEqualTo(2);
        assertThat(eventsFor(id)).as("seed event plus exactly one transition event").hasSize(2);
    }

    private List<InstructionEventEntity> eventsFor(UUID instructionId) {
        return events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instructionId))
                .sorted(Comparator.comparingInt(InstructionEventEntity::getSequenceNo))
                .toList();
    }

    private UUID seedInstruction(InstructionState state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO core.payment_instruction ("
                        + "instruction_id, raw_message_id, uetr, end_to_end_id, state, state_version, "
                        + "debtor_name, debtor_account, debtor_agent_bic, "
                        + "creditor_name, creditor_account, creditor_agent_bic, "
                        + "amount, currency, requested_exec_date"
                        + ") VALUES (?, ?, ?, ?, ?::core.instruction_state, 1, "
                        + "'Debtor', 'ACC-1', 'DEUTDEFFXXX', 'Creditor', 'CRED-ACC', 'CHASUS33XXX', "
                        + "100.00, 'USD', CURRENT_DATE)",
                id, UUID.randomUUID(), UUID.randomUUID(), "E2E-" + id, state.name());
        jdbc.update(
                "INSERT INTO core.instruction_event (instruction_id, sequence_no, from_state, to_state, actor_type, actor_id) "
                        + "VALUES (?, 1, NULL, ?::core.instruction_state, 'SYSTEM', 'seed')",
                id, state.name());
        return id;
    }
}
