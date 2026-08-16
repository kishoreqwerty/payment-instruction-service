package com.kishore.payments.processing.refdata;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * One row of refdata.rail_cutoff: a rail's settlement currency, the amount
 * band it serves ({@code maxAmount} null means uncapped), whether it can
 * settle same-day, and its daily cutoff in its own local time zone.
 */
public record RailDefinition(
        String rail, String currency, BigDecimal minAmount, BigDecimal maxAmount, boolean sameDay, LocalTime cutoffTime, ZoneId cutoffZone) {

    public boolean coversAmount(BigDecimal amount) {
        if (amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }
}
