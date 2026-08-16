package com.kishore.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class HappyPathAndCallbackIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void routedInstructionReachesSettledOnAcscWithAuditEventsInSequence() {
        loadRailScenario("FEDWIRE", acceptAndConfirm("ACSC", null));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("1000.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);
        assertThat(reached).isEqualTo(InstructionState.SETTLED);

        List<InstructionEventEntity> auditTrail = events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instruction.getInstructionId()))
                .sorted((a, b) -> Integer.compare(a.getSequenceNo(), b.getSequenceNo()))
                .toList();
        assertThat(auditTrail).extracting(InstructionEventEntity::getToState)
                .containsExactly(
                        InstructionState.RECEIVED, InstructionState.VALIDATED, InstructionState.ENRICHED,
                        InstructionState.ROUTED, InstructionState.SENT, InstructionState.SETTLED);
    }

    @Test
    void rjctCallbackMovesToExceptionCarryingReasonCode() {
        loadRailScenario("FEDWIRE", acceptAndConfirm("RJCT", "AC04"));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("500.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.EXCEPTION);
        assertThat(reached).isEqualTo(InstructionState.EXCEPTION);

        InstructionEventEntity last = lastEventFor(instruction.getInstructionId());
        assertThat(last.getToState()).isEqualTo(InstructionState.EXCEPTION);
        assertThat(last.getReasonCode()).isEqualTo("AC04");
    }

    @Test
    void duplicateAcscCallbackIsIdempotent() {
        loadRailScenario("FEDWIRE", acceptAndConfirm("ACSC", null));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("250.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);

        int eventsBefore = countEventsFor(instruction.getInstructionId());

        // A second, independent ACSC for the same UETR, as if the rail sent a late duplicate.
        postStatusCallback("FEDWIRE", pacs002Xml(instruction.getUetr().toString(), "ACSC", null));

        // No new transition: still SETTLED, same event count.
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SETTLED);
        assertThat(countEventsFor(instruction.getInstructionId())).isEqualTo(eventsBefore);
    }

    @Test
    void statusCallbackForUnknownUetrIsAcceptedAndCreatesNoInstruction() {
        long before = instructions.count();

        ResponseEntity<String> response = postStatusCallback("FEDWIRE", pacs002Xml(UUID.randomUUID().toString(), "ACSC", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(instructions.count()).isEqualTo(before);
    }

    @Test
    void acscForInstructionAtSentUnconfirmedResolvesToSentThenSettled() {
        // TIMEOUT with recordBeforeTimeout: false leaves the instruction
        // SENT_UNCONFIRMED with no confirmation ever scheduled by the
        // simulator itself; the callback below is posted by hand, standing
        // in for a confirmation that arrives after the client gave up.
        loadRailScenario("FEDWIRE", """
                rail: sent-unconfirmed-then-acsc
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: false
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("750.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        InstructionState ambiguous = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        assertThat(ambiguous).isEqualTo(InstructionState.SENT_UNCONFIRMED);

        postStatusCallback("FEDWIRE", pacs002Xml(instruction.getUetr().toString(), "ACSC", null));

        InstructionState settled = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);
        assertThat(settled).isEqualTo(InstructionState.SETTLED);

        List<InstructionState> toStates = events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instruction.getInstructionId()))
                .sorted((a, b) -> Integer.compare(a.getSequenceNo(), b.getSequenceNo()))
                .map(InstructionEventEntity::getToState)
                .toList();
        assertThat(toStates).endsWith(InstructionState.SENT_UNCONFIRMED, InstructionState.SENT, InstructionState.SETTLED);
    }

    private ResponseEntity<String> postStatusCallback(String railId, String pacs002Xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.postForEntity(callbackStatusUrl(railId), new HttpEntity<>(pacs002Xml, headers), String.class);
    }

    private InstructionEventEntity lastEventFor(UUID instructionId) {
        return events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instructionId))
                .max((a, b) -> Integer.compare(a.getSequenceNo(), b.getSequenceNo()))
                .orElseThrow();
    }

    private int countEventsFor(UUID instructionId) {
        return (int) events.findAll().stream().filter(e -> e.getInstructionId().equals(instructionId)).count();
    }

    private String acceptAndConfirm(String confirmation, String rejectReasonCode) {
        return """
                rail: happy-path
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: %s
                  confirmationDelayMs: 100
                  rejectReasonCode: %s
                statusCallbackUrl: "%s"
                rules: []
                """.formatted(confirmation, rejectReasonCode, callbackStatusUrl("FEDWIRE"));
    }

    private String pacs002Xml(String uetr, String txStatus, String reasonCode) {
        String reasonBlock = reasonCode == null ? "" : "<StsRsnInf><Rsn><Cd>" + reasonCode + "</Cd></Rsn></StsRsnInf>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                    <FIToFIPmtStsRpt>
                        <GrpHdr>
                            <MsgId>MSGID0001</MsgId>
                            <CreDtTm>2026-08-16T10:00:00Z</CreDtTm>
                        </GrpHdr>
                        <TxInfAndSts>
                            <OrgnlEndToEndId>E2E-TEST</OrgnlEndToEndId>
                            <OrgnlTxId>TX-TEST</OrgnlTxId>
                            <OrgnlUETR>%s</OrgnlUETR>
                            <TxSts>%s</TxSts>
                            %s
                        </TxInfAndSts>
                    </FIToFIPmtStsRpt>
                </Document>
                """.formatted(uetr, txStatus, reasonBlock);
    }
}
