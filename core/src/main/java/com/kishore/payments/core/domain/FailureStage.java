package com.kishore.payments.core.domain;

/** The pipeline stage in which an instruction failed. */
public enum FailureStage {
    INTAKE,
    VALIDATION,
    ENRICHMENT,
    ROUTING,
    DISPATCH,
    CONFIRMATION,
    RECONCILIATION
}
