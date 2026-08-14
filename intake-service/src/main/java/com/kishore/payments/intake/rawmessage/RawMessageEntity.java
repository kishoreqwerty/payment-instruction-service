package com.kishore.payments.intake.rawmessage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "raw_message", schema = "intake")
public class RawMessageEntity {

    // Generated in application code rather than left to a JPA/Hibernate
    // generator: the column has no DEFAULT in the schema, and assigning it
    // explicitly avoids depending on Hibernate-version-specific UUID
    // generation behaviour.
    @Id
    @Column(name = "raw_message_id")
    private UUID rawMessageId;

    @Column(name = "received_at", insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "source_channel", nullable = false)
    private String sourceChannel;

    @Column(name = "source_identifier")
    private String sourceIdentifier;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "payload_sha256", nullable = false)
    private byte[] payloadSha256;

    @Column(name = "schema_valid", nullable = false)
    private boolean schemaValid;

    protected RawMessageEntity() {
        // JPA
    }

    public RawMessageEntity(
            String sourceChannel, String sourceIdentifier, String contentType, byte[] payload, byte[] payloadSha256) {
        this.rawMessageId = UUID.randomUUID();
        this.sourceChannel = sourceChannel;
        this.sourceIdentifier = sourceIdentifier;
        this.contentType = contentType;
        this.payload = payload;
        this.payloadSha256 = payloadSha256;
        // Persisted before parsing happens; flipped to true by a later update
        // once XSD validation succeeds. Never left null -- the column is
        // NOT NULL because "we haven't checked yet" and "it failed" are the
        // same fact until validation actually runs.
        this.schemaValid = false;
    }

    public UUID getRawMessageId() {
        return rawMessageId;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public String getSourceIdentifier() {
        return sourceIdentifier;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public byte[] getPayloadSha256() {
        return payloadSha256;
    }

    public boolean isSchemaValid() {
        return schemaValid;
    }
}
