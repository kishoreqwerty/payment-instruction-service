package com.kishore.payments.core.domain;

/** Whether, and how, a failed instruction can be repaired. */
public enum Repairability {
    REPAIRABLE,
    STATIC_DATA,
    TRANSIENT,
    UNREPAIRABLE
}
