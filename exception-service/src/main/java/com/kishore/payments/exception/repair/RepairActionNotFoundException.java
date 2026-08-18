package com.kishore.payments.exception.repair;

import java.util.UUID;

public class RepairActionNotFoundException extends RuntimeException {

    public RepairActionNotFoundException(UUID actionId) {
        super("No repair action: " + actionId);
    }
}
