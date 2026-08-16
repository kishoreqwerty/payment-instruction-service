package com.kishore.payments.processing.refdata;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Plain JDBC reads of the refdata schema. Every one of these tables is small, read-mostly, and looked up by natural key -- JPA's optimistic-locking machinery earns nothing here that {@link com.kishore.payments.core.state.InstructionStateWriter} needs it for. */
@Component
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CorrespondentRelationship> loadCorrespondents() {
        return jdbc.query(
                "SELECT creditor_agent_bic, correspondent_bic, settlement_currency FROM refdata.correspondent",
                (rs, rowNum) -> new CorrespondentRelationship(
                        rs.getString("creditor_agent_bic"), rs.getString("correspondent_bic"), rs.getString("settlement_currency")));
    }

    public List<NostroAccount> loadNostroAccounts() {
        return jdbc.query(
                "SELECT correspondent_bic, currency, nostro_account FROM refdata.nostro_account",
                (rs, rowNum) -> new NostroAccount(rs.getString("correspondent_bic"), rs.getString("currency"), rs.getString("nostro_account")));
    }

    public List<RailDefinition> loadRailDefinitions() {
        return jdbc.query(
                "SELECT rail, currency, min_amount, max_amount, same_day, cutoff_time, cutoff_zone FROM refdata.rail_cutoff",
                (rs, rowNum) -> new RailDefinition(
                        rs.getString("rail"),
                        rs.getString("currency"),
                        rs.getBigDecimal("min_amount"),
                        rs.getBigDecimal("max_amount"),
                        rs.getBoolean("same_day"),
                        rs.getTime("cutoff_time").toLocalTime(),
                        ZoneId.of(rs.getString("cutoff_zone"))));
    }

    /** Currency -> the set of dates that are NOT business days for it (weekends are computed, not stored; this is holidays only). */
    public Map<String, Set<LocalDate>> loadBusinessCalendarHolidays() {
        Map<String, Set<LocalDate>> byCurrency = new HashMap<>();
        jdbc.query("SELECT calendar_date, currency FROM refdata.business_calendar", rs -> {
            String currency = rs.getString("currency");
            LocalDate date = rs.getDate("calendar_date").toLocalDate();
            byCurrency.computeIfAbsent(currency, c -> new HashSet<>()).add(date);
        });
        return byCurrency;
    }
}
