package com.kishore.payments.integration;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.exception.ExceptionServiceApplication;
import com.kishore.payments.gateway.SettlementGatewayApplication;
import com.kishore.payments.intake.IntakeServiceApplication;
import com.kishore.payments.processing.ProcessingServiceApplication;
import com.kishore.payments.railsim.RailSimulatorApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Confirmation-stage rail-rejection categories (60 of the 200 cases), the only ones that need the
 * full five-service pipeline -- see {@link EvaluationCaseGeneratorSupport}'s own javadoc for why
 * this runs separately from {@link EvaluationSetGeneratorPartA}. Run explicitly with:
 * {@code mvn -pl integration-tests test -Dtest=EvaluationSetGeneratorPartB -Dgenerator.excludedGroups=}
 */
@Tag("generator")
@Testcontainers
class EvaluationSetGeneratorPartB extends EvaluationCaseGeneratorSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");
    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(1536L * 1024 * 1024));

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    private ConfigurableApplicationContext railContext;
    private ConfigurableApplicationContext gatewayContext;
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
        return "_partB.jsonl";
    }

    @Test
    void generate() throws Exception {
        railContext = ServiceBoot.boot(
                new SpringApplicationBuilder(RailSimulatorApplication.class), POSTGRES, REDPANDA, "rail-simulator",
                ServiceBoot.railSimulatorArgs());
        int railPort = Integer.parseInt(railContext.getEnvironment().getProperty("local.server.port"));

        gatewayContext = ServiceBoot.boot(
                new SpringApplicationBuilder(SettlementGatewayApplication.class), POSTGRES, REDPANDA, "settlement-gateway",
                ServiceBoot.gatewayArgs("http://localhost:" + railPort));
        int gatewayPort = Integer.parseInt(gatewayContext.getEnvironment().getProperty("local.server.port"));
        loadRejectionScenario(railPort, gatewayPort);

        exceptionContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ExceptionServiceApplication.class), POSTGRES, REDPANDA, "exception-service",
                ServiceBoot.exceptionArgs());
        processingContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ProcessingServiceApplication.class), POSTGRES, REDPANDA, "processing-service",
                ServiceBoot.processingArgs());
        intakeContext = ServiceBoot.boot(
                new SpringApplicationBuilder(IntakeServiceApplication.class), POSTGRES, REDPANDA, "intake-service",
                ServiceBoot.intakeArgs());

        generateCategory("rail-account-closed", 15, FailureStage.CONFIRMATION, "AC04", Repairability.UNREPAIRABLE,
                "AC04 (account closed) means no field on this instruction can be corrected to make it succeed -- the "
                        + "creditor's own account is gone. The payment has to return to the originator, who needs a new "
                        + "account from the beneficiary. The deployed system currently assigns this REPAIRABLE "
                        + "unconditionally for every rail rejection (CallbackStatusApplier); this label deliberately "
                        + "disagrees with that.",
                false, i -> fixture().creditorIbanSuffixMarker("AC04").amount(rotatingAmount(i)).build());
        generateCategory("rail-account-blocked", 10, FailureStage.CONFIRMATION, "AC06", Repairability.TRANSIENT,
                "AC06 (account blocked) is often a hold that lifts -- treated as TRANSIENT (retry later) rather than "
                        + "UNREPAIRABLE, but this is genuinely arguable: some blocks are permanent (regulatory, fraud) "
                        + "and would actually be UNREPAIRABLE. Flagged ambiguous.",
                true, i -> fixture().creditorIbanSuffixMarker("AC06").amount(rotatingAmount(i)).build());
        generateCategory("rail-insufficient-funds", 10, FailureStage.CONFIRMATION, "AM04", Repairability.TRANSIENT,
                "AM04 (insufficient funds) is the debtor's account, not a data defect on this instruction -- retrying "
                        + "later (after funds arrive) is the only lever, no field to change.",
                false, i -> fixture().creditorIbanSuffixMarker("AM04").amount(rotatingAmount(i)).build());
        generateCategory("rail-inconsistent-end-customer", 10, FailureStage.CONFIRMATION, "BE01", Repairability.REPAIRABLE,
                "BE01 (inconsistent with end customer) is typically a name/address mismatch the rail can flag but an "
                        + "operator can correct and resubmit -- REPAIRABLE.",
                false, i -> fixture().creditorIbanSuffixMarker("BE01").amount(rotatingAmount(i)).build());
        generateCategory("rail-end-customer-deceased", 8, FailureStage.CONFIRMATION, "MD07", Repairability.UNREPAIRABLE,
                "MD07 (end customer deceased) has no repair: the payment must return to the originator regardless of "
                        + "what field is changed. The deployed system currently assigns REPAIRABLE unconditionally for "
                        + "every rail rejection; this label deliberately disagrees with that, the same as AC04.",
                false, i -> fixture().creditorIbanSuffixMarker("MD07").amount(rotatingAmount(i)).build());
        generateCategory("rail-regulatory-reason", 7, FailureStage.CONFIRMATION, "RR04", Repairability.UNREPAIRABLE,
                "RR04 (regulatory reason) is a compliance block, not an ops-team repair -- but genuinely arguable: "
                        + "sometimes providing additional information (purpose of payment, beneficiary detail) does "
                        + "satisfy the regulator, which would make this REPAIRABLE-via-additional-data instead. "
                        + "Flagged ambiguous.",
                true, i -> fixture().creditorIbanSuffixMarker("RR04").amount(rotatingAmount(i)).build());

        writeOutput();
    }
}
