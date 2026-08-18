package com.kishore.payments.exception.repair;

import java.util.UUID;

/**
 * The user approving a repair action is the same user who proposed it.
 * Mapped to 403. This is the application-layer half of the maker-checker
 * control -- {@code ck_maker_checker} in the database is the other,
 * independent half; see .notes/ARCHITECTURE.md §8 for why both exist.
 */
public class MakerCheckerViolationException extends RuntimeException {

    public MakerCheckerViolationException(UUID actionId, String user) {
        super("Action " + actionId + " was proposed by " + user + "; the same user cannot approve it");
    }
}
