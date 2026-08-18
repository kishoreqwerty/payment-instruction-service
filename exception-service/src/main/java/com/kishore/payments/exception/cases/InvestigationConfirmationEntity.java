package com.kishore.payments.exception.cases;

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

/**
 * Mirrors exceptions.investigation_confirmation. The maker-checker half of
 * INVESTIGATION confirm-sent, structurally identical to {@code
 * RepairActionEntity} -- one proposal, one approval, one database
 * constraint neither this class nor its caller can bypass -- but a distinct
 * table rather than a reuse of repair_action, since a confirm-sent has no
 * field_path/old_value/new_value to carry, only the justification and
 * evidence the maker relied on.
 */
@Entity
@Table(name = "investigation_confirmation", schema = "exceptions")
public class InvestigationConfirmationEntity implements Persistable<UUID> {

    @Id
    @Column(name = "confirmation_id")
    private UUID confirmationId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "justification", nullable = false)
    private String justification;

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

    protected InvestigationConfirmationEntity() {
        // JPA
    }

    public InvestigationConfirmationEntity(UUID confirmationId, UUID caseId, String justification, String proposedBy) {
        this.confirmationId = confirmationId;
        this.caseId = caseId;
        this.justification = justification;
        this.proposedBy = proposedBy;
    }

    @Override
    public UUID getId() {
        return confirmationId;
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

    public UUID getConfirmationId() {
        return confirmationId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getJustification() {
        return justification;
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
