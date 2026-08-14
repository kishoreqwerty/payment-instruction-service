package com.kishore.payments.core.domain;

import java.util.Objects;
import java.util.UUID;

/** A Unique End-to-end Transaction Reference, per ISO 20022. */
public record Uetr(UUID value) {

    public Uetr {
        Objects.requireNonNull(value, "value");
    }

    public static Uetr generate() {
        return new Uetr(UUID.randomUUID());
    }

    public static Uetr parse(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return new Uetr(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid UETR: " + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
