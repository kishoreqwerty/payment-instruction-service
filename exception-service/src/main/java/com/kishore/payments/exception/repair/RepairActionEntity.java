package com.kishore.payments.exception.repair;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Mirrors exceptions.repair_action. One row per proposed field change; {@code ck_maker_checker} is the database's own copy of the rule this class's caller also enforces in application code. */
@Entity
@Table(name = "repair_action", schema = "exceptions")
public class RepairActionEntity implements Persistable<UUID> {

    @Id
    @Column(name = "action_id")
    private UUID actionId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "field_path", nullable = false)
    private String fieldPath;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value", nullable = false)
    private String newValue;

    @Column(name = "proposed_by", nullable = false)
    private String proposedBy;

    @Column(name = "proposed_at", insertable = false, updatable = false)
    private OffsetDateTime proposedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Transient
    private boolean isNew = true;

    protected RepairActionEntity() {
        // JPA
    }

    public RepairActionEntity(UUID actionId, UUID caseId, String fieldPath, String oldValue, String newValue, String proposedBy) {
        this.actionId = actionId;
        this.caseId = caseId;
        this.fieldPath = fieldPath;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.proposedBy = proposedBy;
    }

    @Override
    public UUID getId() {
        return actionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public UUID getActionId() {
        return actionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getFieldPath() {
        return fieldPath;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public OffsetDateTime getProposedAt() {
        return proposedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public boolean isApproved() {
        return approvedBy != null;
    }

    public void approve(String approvedBy, OffsetDateTime approvedAt) {
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
    }
}
