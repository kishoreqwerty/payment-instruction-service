package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Found with ~106 cases loaded against a hardcoded page size of 100: the queue's own case count
 * (an operator's workload gauge) was reporting the returned row count rather than the backend's
 * true match total, and cases sharing an {@code openedAt} value (the only sort key the queue
 * ever requests) had no defined relative order, so repeated identical queries could return them
 * in a different order -- read as the queue reshuffling between refreshes.
 *
 * <p>Every assertion here scopes to a single test's own cases via a random {@code assignedTo}
 * marker: {@link AbstractExceptionServiceIntegrationTest}'s Postgres container is a static field
 * on the shared base class, so its data persists across every test class that runs against it in
 * the same fork, not just this one.
 */
class CaseQueuePaginationTest extends AbstractExceptionServiceIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void totalElementsReflectsTheFullMatchCountAcrossMultiplePages() throws Exception {
        String marker = "pg-total-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            openCase(marker, "AC01");
        }

        JsonNode firstPage = getCasesJson("assignedTo=" + marker + "&size=2&page=0");
        assertThat(firstPage.path("totalElements").asInt()).isEqualTo(5);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.path("content")).hasSize(2);

        JsonNode lastPage = getCasesJson("assignedTo=" + marker + "&size=2&page=2");
        assertThat(lastPage.path("totalElements").asInt()).isEqualTo(5);
        assertThat(lastPage.path("content")).hasSize(1);
    }

    @Test
    void totalElementsForAFilteredQueryDiffersFromUnfiltered() throws Exception {
        String marker = "pg-filter-" + UUID.randomUUID();
        openCase(marker, "AC01");
        openCase(marker, "AC01");
        openCase(marker, "AC01");
        openCase(marker, "RC01");
        openCase(marker, "RC01");

        JsonNode unfiltered = getCasesJson("assignedTo=" + marker + "&size=200");
        JsonNode filtered = getCasesJson("assignedTo=" + marker + "&reasonCode=AC01&size=200");

        assertThat(unfiltered.path("totalElements").asInt()).isEqualTo(5);
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    void resultsAreStablyOrderedWhenTheRequestedSortKeyTies() throws Exception {
        String marker = "pg-order-" + UUID.randomUUID();
        List<UUID> caseIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            caseIds.add(openCase(marker, "AC01"));
        }
        // Force an exact tie on the only sort key the queue ever requests -- bulk-seeded or
        // fast-succession cases can share this in practice too, which is what exposed the bug.
        OffsetDateTime tiedOpenedAt = OffsetDateTime.now(clock);
        for (UUID caseId : caseIds) {
            jdbc.update("UPDATE exceptions.exception_case SET opened_at = ? WHERE case_id = ?", tiedOpenedAt, caseId);
        }

        // The database's own answer to "ascending by case_id" -- the authoritative expected
        // order, not a Java-side re-derivation (Java's UUID.compareTo() does not agree with
        // Postgres's UUID ordering in general, so re-sorting caseIds in Java here would test the
        // wrong thing).
        List<UUID> expectedOrder = jdbc.queryForList(
                "SELECT case_id FROM exceptions.exception_case WHERE assigned_to = ? ORDER BY case_id ASC", UUID.class, marker);

        List<UUID> firstCall = orderedCaseIds(marker);
        List<UUID> secondCall = orderedCaseIds(marker);

        assertThat(firstCall).isEqualTo(expectedOrder);
        assertThat(secondCall).isEqualTo(expectedOrder);
    }

    private UUID openCase(String assignedToMarker, String reasonCode) {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN-" + UUID.randomUUID());
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail(reasonCode, Repairability.REPAIRABLE, "creditorAccount", "seeded for pagination test"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        jdbc.update("UPDATE exceptions.exception_case SET assigned_to = ? WHERE case_id = ?", assignedToMarker, opened.getCaseId());
        return opened.getCaseId();
    }

    private JsonNode getCasesJson(String query) throws Exception {
        ResponseEntity<String> response = asUser("viewer").getForEntity(url("/v1/cases?" + query), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private List<UUID> orderedCaseIds(String marker) throws Exception {
        JsonNode body = getCasesJson("assignedTo=" + marker + "&sort=openedAt,asc&size=200");
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : body.path("content")) {
            ids.add(UUID.fromString(node.path("caseId").asText()));
        }
        return ids;
    }
}
