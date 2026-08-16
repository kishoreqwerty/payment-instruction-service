package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Each failure taxonomy row (.notes/ARCHITECTURE.md §6.1) produces the
 * correct ISO reason code and repairability on the instruction's own
 * EXCEPTION-transition event -- verified against the real database, not a
 * mock of the taxonomy.
 */
class FailureTaxonomyIntegrationTest extends AbstractProcessingIntegrationTest {

    @Test
    void invalidIbanProducesAc01Repairable() {
        PaymentInstructionEntity instruction = entity(
                "DE00370400440532013000", // corrupted check digits
                "DEUTDEFFXXX",
                "DEUTDEFFXXX",
                new BigDecimal("500.00"),
                "EUR",
                LocalDate.now(clock));

        assertExceptionOutcome(instruction, "AC01", Repairability.REPAIRABLE);
    }

    @Test
    void malformedCreditorAgentBicProducesRc01Repairable() {
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000", "DEUTDEFFXXX", "NOTABIC", new BigDecimal("500.00"), "EUR", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "RC01", Repairability.REPAIRABLE);
    }

    @Test
    void amountBelowEveryConfiguredRailsFloorProducesAm02Repairable() {
        // 0.001 EUR is positive (passes the DB's amount > 0 check) but below
        // every configured rail's 0.01 minimum for EUR -- see
        // V2__refdata_schema.sql. With FEDWIRE/ACH_EQUIV's overlapping bands
        // covering all of USD's positive range, a sub-cent amount is the one
        // gap this fixture set actually leaves open to test against.
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000", "DEUTDEFFXXX", "DEUTDEFFXXX", new BigDecimal("0.001"), "EUR", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "AM02", Repairability.REPAIRABLE);
    }

    @Test
    void currencyInconsistentWithDebtorCountryProducesNoCodeRepairable() {
        // German (EUR-zone) debtor IBAN, USD currency.
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000", "DEUTDEFFXXX", "CHASUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));

        assertExceptionOutcome(instruction, null, Repairability.REPAIRABLE);
    }

    @Test
    void pastRequestedExecutionDateProducesNoCodeRepairable() {
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "DEUTDEFFXXX",
                new BigDecimal("500.00"),
                "EUR",
                LocalDate.now(clock).minusDays(1));

        assertExceptionOutcome(instruction, null, Repairability.REPAIRABLE);
    }

    @Test
    void missingCorrespondentProducesRc01StaticData() {
        // Well-formed, unknown BIC -- no refdata.correspondent row for it.
        // Polish debtor account (not in CurrencyConsistentWithDebtorCountryRule's
        // table) so a USD payment doesn't also trip that rule.
        PaymentInstructionEntity instruction = entity(
                "PL61109010140000071219812874", "CHASUS33XXX", "UNKNUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "RC01", Repairability.STATIC_DATA);
    }

    @Test
    void missingNostroAccountProducesRc01StaticData() {
        // SCBLUS33XXX has a correspondent relationship but no nostro account
        // row -- see V2__refdata_schema.sql.
        PaymentInstructionEntity instruction = entity(
                "PL61109010140000071219812874", "CHASUS33XXX", "SCBLUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "RC01", Repairability.STATIC_DATA);
    }

    @Test
    void noEligibleRailProducesAg01Repairable() {
        // NWBKGB2LXXX resolves a correspondent and nostro account in GBP,
        // but refdata.rail_cutoff has no GBP rail at all -- enrichment
        // succeeds and only routing fails.
        PaymentInstructionEntity instruction =
                entity("GB29NWBK60161331926819", "CHASUS33XXX", "NWBKGB2LXXX", new BigDecimal("500.00"), "GBP", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "AG01", Repairability.REPAIRABLE);
    }

    private void assertExceptionOutcome(PaymentInstructionEntity instruction, String expectedReasonCode, Repairability expectedRepairability) {
        seedReceived(instruction);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState).isEqualTo(InstructionState.EXCEPTION);

        InstructionEventEntity exceptionEvent = events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instruction.getInstructionId()))
                .filter(e -> e.getToState() == InstructionState.EXCEPTION)
                .max(Comparator.comparingInt(InstructionEventEntity::getSequenceNo))
                .orElseThrow();

        assertThat(exceptionEvent.getReasonCode()).isEqualTo(expectedReasonCode);

        // The exception outbox event carries repairability per-detail, not
        // on the instruction_event row -- check the outbox payload directly.
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM core.outbox WHERE aggregate_id = ? AND topic = 'payments.exceptions'",
                String.class,
                instruction.getInstructionId());
        assertThat(payload).contains(expectedRepairability.name());
        if (expectedReasonCode != null) {
            assertThat(payload).contains(expectedReasonCode);
        }
    }

    private static PaymentInstructionEntity entity(
            String debtorAccount, String debtorAgentBic, String creditorAgentBic, BigDecimal amount, String currency, LocalDate requestedExecDate) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor",
                debtorAccount,
                debtorAgentBic,
                "Creditor",
                "FR1420041010050500013M02606",
                creditorAgentBic,
                amount,
                currency,
                null,
                requestedExecDate);
    }
}
