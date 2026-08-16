package com.kishore.payments.processing.refdata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The cached read path enrichment and routing use for every lookup against
 * refdata. Each of the four tables is cached whole, keyed by
 * {@link RefdataVersionProvider#currentVersion()} rather than by individual
 * lookup key: the tables are small (a handful of rows each in this phase's
 * fixture data), so caching the whole table per version and filtering in
 * memory is simpler than a cache entry per BIC/currency pair, and it means a
 * version bump invalidates everything at once rather than leaving some keys
 * stale.
 */
@Component
public class ReferenceDataService {

    private final ReferenceDataRepository repository;
    private final RefdataVersionProvider versionProvider;
    private final Cache<Long, List<CorrespondentRelationship>> correspondents;
    private final Cache<Long, List<NostroAccount>> nostroAccounts;
    private final Cache<Long, List<RailDefinition>> railDefinitions;
    private final Cache<Long, Map<String, Set<LocalDate>>> businessCalendarHolidays;

    public ReferenceDataService(ReferenceDataRepository repository, RefdataVersionProvider versionProvider) {
        this.repository = repository;
        this.versionProvider = versionProvider;
        // maximumSize is generous relative to how many distinct versions
        // could realistically be in flight at once; this is a safety bound
        // against unbounded growth, not a working-set sizing decision.
        this.correspondents = Caffeine.newBuilder().maximumSize(64).build();
        this.nostroAccounts = Caffeine.newBuilder().maximumSize(64).build();
        this.railDefinitions = Caffeine.newBuilder().maximumSize(64).build();
        this.businessCalendarHolidays = Caffeine.newBuilder().maximumSize(64).build();
    }

    public long currentVersion() {
        return versionProvider.currentVersion();
    }

    public Optional<CorrespondentRelationship> findCorrespondent(String creditorAgentBic) {
        return correspondentsForCurrentVersion().stream()
                .filter(c -> c.creditorAgentBic().equalsIgnoreCase(creditorAgentBic))
                .findFirst();
    }

    public Optional<NostroAccount> findNostroAccount(String correspondentBic, String currency) {
        return nostroAccountsForCurrentVersion().stream()
                .filter(n -> n.correspondentBic().equalsIgnoreCase(correspondentBic) && n.currency().equalsIgnoreCase(currency))
                .findFirst();
    }

    public List<RailDefinition> railsFor(String currency) {
        return railDefinitionsForCurrentVersion().stream()
                .filter(r -> r.currency().equalsIgnoreCase(currency))
                .toList();
    }

    /** Weekends are computed, never stored; refdata.business_calendar carries only the holiday exceptions. */
    public boolean isBusinessDay(LocalDate date, String currency) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidaysForCurrentVersion().getOrDefault(currency, Set.of()).contains(date);
    }

    private List<CorrespondentRelationship> correspondentsForCurrentVersion() {
        return correspondents.get(currentVersion(), v -> repository.loadCorrespondents());
    }

    private List<NostroAccount> nostroAccountsForCurrentVersion() {
        return nostroAccounts.get(currentVersion(), v -> repository.loadNostroAccounts());
    }

    private List<RailDefinition> railDefinitionsForCurrentVersion() {
        return railDefinitions.get(currentVersion(), v -> repository.loadRailDefinitions());
    }

    private Map<String, Set<LocalDate>> holidaysForCurrentVersion() {
        return businessCalendarHolidays.get(currentVersion(), v -> repository.loadBusinessCalendarHolidays());
    }
}
