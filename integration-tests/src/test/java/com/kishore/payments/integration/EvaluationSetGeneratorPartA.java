package com.kishore.payments.integration;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.exception.ExceptionServiceApplication;
import com.kishore.payments.intake.IntakeServiceApplication;
import com.kishore.payments.processing.ProcessingServiceApplication;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Validation/enrichment/routing-stage categories (140 of the 200 cases), needing only three
 * services -- see {@link EvaluationCaseGeneratorSupport}'s own javadoc for why this is split from
 * {@link EvaluationSetGeneratorPartB} at all. Run explicitly with:
 * {@code mvn -pl integration-tests test -Dtest=EvaluationSetGeneratorPartA -Dgenerator.excludedGroups=}
 */
@Tag("generator")
@Testcontainers
class EvaluationSetGeneratorPartA extends EvaluationCaseGeneratorSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1536L * 1024 * 1024));

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    private ConfigurableApplicationContext exceptionContext;
    private ConfigurableApplicationContext processingContext;
    private ConfigurableApplicationContext intakeContext;

    @AfterAll
    static void tearDownContainers() {
        REDPANDA.stop();
        POSTGRES.stop();
    }

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @Override
    ConfigurableApplicationContext intakeContext() {
        return intakeContext;
    }

    @Override
    ConfigurableApplicationContext processingContext() {
        return processingContext;
    }

    @Override
    String outputFileName() {
        return "_partA.jsonl";
    }

    @Test
    void generate() throws Exception {
        exceptionContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ExceptionServiceApplication.class), POSTGRES, REDPANDA, "exception-service",
                ServiceBoot.exceptionArgs());
        processingContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ProcessingServiceApplication.class), POSTGRES, REDPANDA, "processing-service",
                ServiceBoot.processingArgs());
        intakeContext = ServiceBoot.boot(
                new SpringApplicationBuilder(IntakeServiceApplication.class), POSTGRES, REDPANDA, "intake-service",
                ServiceBoot.intakeArgs());

        // -- Validation stage --------------------------------------------------------------
        // No "amount fits no rail" (AM02) category: the seeded refdata makes this rule
        // unreachable with a positive amount -- ACH_EQUIV (0.01-149999.99) and FEDWIRE
        // (>=100000, no max) fully tile every positive USD amount between them, and SEPA
        // (0.01, no max) fully tiles every positive EUR amount alone. A zero/negative amount hits
        // the database's own amount > 0 check constraint at intake, before this rule (or any
        // processing-service validation at all) ever runs. Genuinely unreachable with the current
        // reference data, not a gap in this generator -- redistributed to the categories below.
        generateCategory("debtor-iban-invalid", 30, FailureStage.VALIDATION, "AC01", Repairability.REPAIRABLE,
                "Debtor IBAN fails the mod-97 checksum -- a data-entry error, textbook AC01/REPAIRABLE.", false,
                i -> fixture().debtorIban(corruptedIban("DE", "370400440532013000")).amount(rotatingAmount(i)).build());
        generateCategory("creditor-iban-invalid", 25, FailureStage.VALIDATION, "AC01", Repairability.REPAIRABLE,
                "Creditor IBAN fails the mod-97 checksum -- same reasoning as the debtor side.", false,
                i -> fixture().creditorIban(corruptedIban("FR", "20041010050500013456789")).amount(rotatingAmount(i)).build());
        generateCategory("debtor-bic-malformed", 15, FailureStage.VALIDATION, "RC01", Repairability.REPAIRABLE,
                "Debtor agent BIC does not match the ISO 9362 format at all -- a typo, not a reference-data gap.", false,
                i -> fixture().debtorBic("1234XXAB").amount(rotatingAmount(i)).build());
        generateCategory("creditor-bic-malformed", 15, FailureStage.VALIDATION, "RC01", Repairability.REPAIRABLE,
                "Creditor agent BIC does not match the ISO 9362 format at all.", false,
                i -> fixture().creditorBic("1234YYCD").amount(rotatingAmount(i)).build());
        generateCategory("currency-country-mismatch", 10, FailureStage.VALIDATION, null, Repairability.REPAIRABLE,
                "German (single-currency, EUR-only) debtor IBAN paired with a USD payment -- a plausibility check, not "
                        + "a rail-defined defect, hence no ISO code.",
                false,
                i -> fixture().debtorIban(validIban("DE", "370400440532013000")).currency("USD").creditorBic("TESTUS33")
                        .amount(rotatingAchOnlyAmount(i)).build());
        generateCategory("requested-date-in-the-past", 10, FailureStage.VALIDATION, null, Repairability.REPAIRABLE,
                "Requested execution date is before today (this system's UTC clock) -- correctable by resubmitting or "
                        + "amending the date, no ISO code assigned by this rule.",
                false, i -> fixture().requestedExecDate(LocalDate.now(ZoneOffset.UTC).minusDays(3)).amount(rotatingAmount(i)).build());

        // -- Enrichment / routing stage ----------------------------------------------------
        generateCategory("no-eligible-rail", 10, FailureStage.ROUTING, "AG01", Repairability.REPAIRABLE,
                "Correspondent and nostro exist for GBP, but no GBP rail is configured at all -- a genuine routing gap, "
                        + "but one an operator can work around (alternate corridor/rail), not a hard block.",
                false,
                i -> fixture().debtorIban(validIban("AE", "1234567890123456789")).currency("GBP").creditorBic("NWBKGB2LXXX")
                        .amount(rotatingAmount(i)).build());
        generateCategory("no-correspondent", 15, FailureStage.ENRICHMENT, "RC01", Repairability.STATIC_DATA,
                "Creditor agent BIC has no correspondent-banking relationship on file at all -- a reference-data gap, "
                        + "not something an operator can fix by editing this instruction's own fields.",
                false, i -> fixture().creditorBic("TESTGB22").amount(rotatingAmount(i)).build());
        generateCategory("no-nostro-account", 10, FailureStage.ENRICHMENT, "RC01", Repairability.STATIC_DATA,
                "Correspondent relationship exists for USD, but no nostro account is on file for it -- same STATIC_DATA "
                        + "reasoning as a missing correspondent, a different specific gap.",
                false,
                i -> fixture().debtorIban(validIban("AE", "1234567890123456789")).currency("USD").creditorBic("SCBLUS33XXX")
                        .amount(rotatingAchOnlyAmount(i)).build());

        writeOutput();
    }
}
