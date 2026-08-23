package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import com.kishore.payments.processing.enrichment.EnrichmentChain;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.validation.ValidationChain;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Each failure taxonomy row (.notes/ARCHITECTURE.md §6.1) produces the
 * correct ISO reason code and repairability on the instruction's own
 * EXCEPTION-transition event -- verified against the real database, not a
 * mock of the taxonomy.
 */
class FailureTaxonomyIntegrationTest extends AbstractProcessingIntegrationTest {

    @Autowired
    private ValidationChain validationChain;

    @Autowired
    private EnrichmentChain enrichmentChain;

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
    void currencyInconsistentWithDebtorCountryProducesCurrRepairable() {
        // German (EUR-zone) debtor IBAN, USD currency.
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000", "DEUTDEFFXXX", "CHASUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));

        assertExceptionOutcome(instruction, "CURR", Repairability.REPAIRABLE);
    }

    @Test
    void pastRequestedExecutionDateProducesDt01Repairable() {
        PaymentInstructionEntity instruction = entity(
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "DEUTDEFFXXX",
                new BigDecimal("500.00"),
                "EUR",
                LocalDate.now(clock).minusDays(1));

        assertExceptionOutcome(instruction, "DT01", Repairability.REPAIRABLE);
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

    /**
     * Runs every {@link ValidationChain}-registered rule against one instruction engineered to
     * trip it -- and only it -- and asserts every resulting {@link FailureDetail} carries a
     * non-null reason code. {@code validationChain} is Spring-autowired with the real {@code
     * List<ValidationRule>} (see {@code ValidationChain}'s own constructor), so a new rule added
     * later is picked up here automatically; it only fails to be *exercised* if none of these
     * fixtures happen to trigger it, same as any behaviour-driven test. This is the standing
     * regression test for the gap this class's own history already hit twice (currency, past
     * execution date): a rule that validates correctly but forgets to name a reason code.
     */
    @Test
    void everyValidationRuleCarriesAReasonCode() {
        List<PaymentInstructionEntity> oneDefectEach = List.of(
                entity("DE00370400440532013000", "DEUTDEFFXXX", "DEUTDEFFXXX", new BigDecimal("500.00"), "EUR", LocalDate.now(clock)),
                entity("DE89370400440532013000", "NOTABIC", "DEUTDEFFXXX", new BigDecimal("500.00"), "EUR", LocalDate.now(clock)),
                entity("DE89370400440532013000", "DEUTDEFFXXX", "NOTABIC", new BigDecimal("500.00"), "EUR", LocalDate.now(clock)),
                entity("DE89370400440532013000", "DEUTDEFFXXX", "DEUTDEFFXXX", new BigDecimal("0.001"), "EUR", LocalDate.now(clock)),
                entity("DE89370400440532013000", "DEUTDEFFXXX", "CHASUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock)),
                entity(
                        "DE89370400440532013000", "DEUTDEFFXXX", "DEUTDEFFXXX", new BigDecimal("500.00"), "EUR",
                        LocalDate.now(clock).minusDays(1)));

        List<FailureDetail> everyViolationSeen = new java.util.ArrayList<>();
        for (PaymentInstructionEntity fixture : oneDefectEach) {
            List<FailureDetail> violations = validationChain.validate(fixture);
            assertThat(violations).as("fixture %s was expected to trigger at least one rule", fixture.getDebtorAccount()).isNotEmpty();
            everyViolationSeen.addAll(violations);
        }

        assertThat(everyViolationSeen).allSatisfy(v -> assertThat(v.reasonCode()).as("%s", v.detail()).isNotNull());
    }

    /**
     * Same invariant as {@link #everyValidationRuleCarriesAReasonCode()}, for the enrichment
     * links capable of producing a {@link FailureDetail} at all ({@code CorrespondentResolutionLink},
     * {@code NostroAccountLink} -- {@code ScreeningLink}'s own reason code is asserted directly in
     * {@code ScreeningLinkTest}, since triggering it needs a non-default {@code ScreeningProvider}
     * this Spring context doesn't wire in; the cutoff/business-day/charge-bearer/refdata-version
     * links never throw at all, per {@code EnrichmentLink}'s own javadoc).
     */
    @Test
    void everyEnrichmentFailureCarriesAReasonCode() {
        PaymentInstructionEntity noCorrespondent = entity(
                "PL61109010140000071219812874", "CHASUS33XXX", "UNKNUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));
        assertThatThrownBy(() -> enrichmentChain.enrich(noCorrespondent))
                .isInstanceOfSatisfying(
                        BusinessFailureException.class,
                        e -> assertThat(e.details()).allSatisfy(d -> assertThat(d.reasonCode()).as("%s", d.detail()).isNotNull()));

        PaymentInstructionEntity noNostro = entity(
                "PL61109010140000071219812874", "CHASUS33XXX", "SCBLUS33XXX", new BigDecimal("500.00"), "USD", LocalDate.now(clock));
        assertThatThrownBy(() -> enrichmentChain.enrich(noNostro))
                .isInstanceOfSatisfying(
                        BusinessFailureException.class,
                        e -> assertThat(e.details()).allSatisfy(d -> assertThat(d.reasonCode()).as("%s", d.detail()).isNotNull()));
    }

    /**
     * {@code expectedReasonCode} is required, never {@code null}: every one of this taxonomy's
     * VALIDATION/ENRICHMENT/ROUTING rows carries a real ISO 20022 external code (see the
     * standalone assertion of that same invariant below, {@link #everyValidationRuleCarriesAReasonCode()}
     * and {@link #everyEnrichmentFailureCarriesAReasonCode()}) -- a taxonomy row without one is
     * exactly the spec gap this class exists to catch, not a legitimate outcome to assert.
     */
    private void assertExceptionOutcome(PaymentInstructionEntity instruction, String expectedReasonCode, Repairability expectedRepairability) {
        assertThat(expectedReasonCode).as("every failure taxonomy row asserted here must carry a real ISO reason code").isNotNull();

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
        assertThat(payload).contains(expectedReasonCode);
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
