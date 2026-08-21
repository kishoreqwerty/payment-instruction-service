package com.kishore.payments.exception.timeline;

import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import com.kishore.payments.exception.cases.InvestigationConfirmationRepository;
import com.kishore.payments.exception.repair.RepairActionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TimelineService {

    private final InstructionEventRepository events;
    private final ExceptionCaseRepository cases;
    private final RepairActionRepository repairActions;
    private final InvestigationConfirmationRepository confirmations;

    public TimelineService(
            InstructionEventRepository events, ExceptionCaseRepository cases, RepairActionRepository repairActions,
            InvestigationConfirmationRepository confirmations) {
        this.events = events;
        this.cases = cases;
        this.repairActions = repairActions;
        this.confirmations = confirmations;
    }

    public List<TimelineEntry> forInstruction(UUID instructionId) {
        List<TimelineEntry> entries = events.findByInstructionIdOrderBySequenceNoAsc(instructionId).stream()
                .map(TimelineEntry::ofTransition)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        List<UUID> caseIds = cases.findByInstructionIdOrderByOpenedAtDesc(instructionId).stream().map(ExceptionCaseEntity::getCaseId).toList();
        if (!caseIds.isEmpty()) {
            for (var action : repairActions.findByCaseIdIn(caseIds)) {
                entries.add(TimelineEntry.ofRepairProposed(action));
                if (action.isApproved()) {
                    entries.add(TimelineEntry.ofRepairApproved(action));
                }
            }
            // See TimelineEntry's own javadoc: Phase 8 wired repair actions into this
            // timeline but never investigation confirmations, so an INVESTIGATION
            // case's timeline silently omitted the one action that resolved it.
            for (var confirmation : confirmations.findByCaseIdIn(caseIds)) {
                entries.add(TimelineEntry.ofConfirmationProposed(confirmation));
                if (confirmation.isApproved()) {
                    entries.add(TimelineEntry.ofConfirmationApproved(confirmation));
                }
            }
        }

        entries.sort(Comparator.comparing(TimelineEntry::occurredAt));
        return entries;
    }
}
