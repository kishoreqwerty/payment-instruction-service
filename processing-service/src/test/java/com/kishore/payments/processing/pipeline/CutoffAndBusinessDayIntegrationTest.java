package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Cutoff and business-day rolling against the real seeded refdata (V2__refdata_schema.sql),
 * driven through the real EnrichmentChain rather than a mocked link -- these
 * are the two enrichment steps the Phase 4 brief calls out as "the ones most
 * likely to be built wrong": a missed cutoff or a non-business date must
 * roll forward and continue, never raise an exception.
 */
@Import(CutoffAndBusinessDayIntegrationTest.MutableClockConfig.class)
class CutoffAndBusinessDayIntegrationTest extends AbstractProcessingIntegrationTest {

    @Autowired
    private MutableClock clock;

    @Test
    void cutoffMissRollsSettlementDateForwardAndContinuesWithoutAnException() {
        // Wednesday 2026-06-17, 16:00 Europe/Paris -- one hour past SEPA's
        // 15:00 cutoff. 2026-06-17T14:00:00Z == 16:00 CEST.
        clock.set(Instant.parse("2026-06-17T14:00:00Z"));
        LocalDate requested = LocalDate.of(2026, 6, 17);

        PaymentInstructionEntity instruction = seedReceivedEurInstruction(new BigDecimal("500.00"), requested);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState).as("a missed cutoff must never produce an EXCEPTION").isEqualTo(InstructionState.ROUTED);

        LocalDate settlementDate = instructions.findById(instruction.getInstructionId()).orElseThrow().getSettlementDate();
        // Rolled forward one day for the missed cutoff; 2026-06-18 (Thursday)
        // is itself a business day, so no further roll applies.
        assertThat(settlementDate).isEqualTo(LocalDate.of(2026, 6, 18));
    }

    @Test
    void aNonBusinessSettlementDateRollsForwardThroughAWeekend() {
        // "Now" is well before the requested date, so this payment is not
        // urgent and the cutoff link applies no push of its own -- only the
        // business-day roll is under test here.
        clock.set(Instant.parse("2026-06-01T09:00:00Z"));
        LocalDate saturday = LocalDate.of(2026, 8, 1);

        PaymentInstructionEntity instruction = seedReceivedEurInstruction(new BigDecimal("500.00"), saturday);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState).isEqualTo(InstructionState.ROUTED);

        LocalDate settlementDate = instructions.findById(instruction.getInstructionId()).orElseThrow().getSettlementDate();
        assertThat(settlementDate).isEqualTo(LocalDate.of(2026, 8, 3)); // Monday
    }

    @Test
    void fridayPastCutoffLandsOnMonday() {
        // Friday 2026-07-10, 18:00 America/New_York -- one hour past
        // FEDWIRE's 17:00 cutoff. 2026-07-10T22:00:00Z == 18:00 EDT.
        clock.set(Instant.parse("2026-07-10T22:00:00Z"));
        LocalDate friday = LocalDate.of(2026, 7, 10);

        // 250,000 USD is above ACH_EQUIV's band entirely, so FEDWIRE is the
        // only eligible rail -- no ambiguity about which cutoff applies.
        PaymentInstructionEntity instruction = seedReceivedUsdInstruction(new BigDecimal("250000.00"), friday);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState).isEqualTo(InstructionState.ROUTED);

        PaymentInstructionEntity reloaded = instructions.findById(instruction.getInstructionId()).orElseThrow();
        assertThat(reloaded.getSelectedRail()).isEqualTo("FEDWIRE");
        // Cutoff link pushes Friday -> Saturday; business-day roll then
        // carries it through Sunday to Monday.
        assertThat(reloaded.getSettlementDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now(), ZoneOffset.UTC);
        }
    }

    /** A Clock whose instant can be changed mid-test, so one Spring context can drive several distinct "now" scenarios without paying for a fresh context each time. */
    static class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant newInstant) {
            instant.set(newInstant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant.get(), newZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
