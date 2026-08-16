package com.kishore.payments.processing.refdata;

/**
 * The version {@link ReferenceDataService}'s cache is keyed on. Bumping it
 * is what makes a refdata change visible: the new version is a cache miss
 * and loads fresh rows, while entries under the old version simply age out
 * rather than being explicitly invalidated. Nothing in Phase 4 writes to
 * refdata after the seed migration, so nothing bumps this yet -- see
 * .notes/reports/PHASE-4-REPORT.md §5 for what that means for an instruction
 * mid-flight when a future phase adds one.
 */
public interface RefdataVersionProvider {

    long currentVersion();
}
