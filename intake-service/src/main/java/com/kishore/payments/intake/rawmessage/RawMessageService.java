package com.kishore.payments.intake.rawmessage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists exactly what a counterparty sent, before any parsing happens.
 * Runs in its own committed transaction (REQUIRES_NEW) so the raw_message row
 * exists regardless of what schema validation later decides -- a malformed or
 * schema-invalid message is exactly the case where knowing what actually
 * arrived matters most.
 */
@Service
public class RawMessageService {

    private final RawMessageRepository repository;

    public RawMessageService(RawMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawMessageEntity persist(byte[] payload, String sourceChannel, String sourceIdentifier, String contentType) {
        byte[] sha256 = sha256(payload);
        RawMessageEntity entity = new RawMessageEntity(sourceChannel, sourceIdentifier, contentType, payload, sha256);
        return repository.save(entity);
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm for every JDK implementation.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}
