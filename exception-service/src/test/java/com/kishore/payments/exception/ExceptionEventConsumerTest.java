package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.CaseType;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion 5: two exception events for one instruction produce
 * exactly one open case, including under real concurrent consumption --
 * proving the partial unique index (not just the check-then-insert
 * happy path) is what actually enforces it.
 */
class ExceptionEventConsumerTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void realKafkaDeliveryOpensACase() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));

        awaitCondition(Duration.ofSeconds(10), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        assertThat(opened.getCaseType()).isEqualTo(CaseType.BUSINESS_FAILURE);
        assertThat(opened.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(opened.getReasonCode()).isEqualTo("AC01");
    }

    @Test
    void aSecondSequentialFailureAppendsRatherThanOpeningASecondCase() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        caseService.handleExceptionEvent(new InstructionExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, java.time.OffsetDateTime.now(clock),
                FailureStage.VALIDATION, List.of(new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "bad IBAN")),
                InstructionExceptionEvent.CURRENT_VERSION));
        caseService.handleExceptionEvent(new InstructionExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 3, java.time.OffsetDateTime.now(clock),
                FailureStage.VALIDATION,
                List.of(new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "bad IBAN, redelivered")),
                InstructionExceptionEvent.CURRENT_VERSION));

        List<ExceptionCaseEntity> allCases = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId());
        assertThat(allCases).hasSize(1);
        assertThat(allCases.get(0).getReasonDetail()).isEqualTo("bad IBAN, redelivered");
    }

    @Test
    void concurrentFirstDeliveriesForTheSameInstructionStillProduceExactlyOneOpenCase() throws Exception {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<?>> futures = IntStream.range(0, n).<Future<?>>mapToObj(i -> pool.submit(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            caseService.handleExceptionEvent(new InstructionExceptionEvent(
                    instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, java.time.OffsetDateTime.now(clock),
                    FailureStage.VALIDATION,
                    List.of(new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "concurrent " + i)),
                    InstructionExceptionEvent.CURRENT_VERSION));
        })).toList();

        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        List<ExceptionCaseEntity> allCases = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId());
        assertThat(allCases).as("uq_one_open_case_per_instruction must have arbitrated every concurrent opener down to one row").hasSize(1);
    }
}
