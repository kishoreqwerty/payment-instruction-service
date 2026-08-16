package com.kishore.payments.railsim.state;

import com.kishore.payments.railsim.scenario.ScenarioConfig;
import com.kishore.payments.railsim.scenario.ScenarioLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One independent {@link RailState} per rail, each started on {@code
 * classpath:scenarios/default.yml} -- everything settles cleanly until a
 * test loads something else via {@code POST /rail/{railId}/scenario}.
 * Independent instances, independent maps: chaos loaded onto FEDWIRE cannot
 * perturb SEPA (§6 of the phase brief), because there is no shared state
 * between them beyond this registry holding a reference to each.
 */
@Component
public class RailStateRegistry {

    private static final String DEFAULT_SCENARIO_CLASSPATH = "/scenarios/default.yml";

    private final Map<RailId, RailState> states = new EnumMap<>(RailId.class);

    public RailStateRegistry(ScenarioLoader scenarioLoader) {
        ScenarioConfig defaultScenario = loadDefault(scenarioLoader);
        for (RailId railId : RailId.values()) {
            states.put(railId, new RailState(railId, defaultScenario));
        }
    }

    public RailState get(RailId railId) {
        RailState state = states.get(railId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown rail: " + railId);
        }
        return state;
    }

    private static ScenarioConfig loadDefault(ScenarioLoader scenarioLoader) {
        try (InputStream in = RailStateRegistry.class.getResourceAsStream(DEFAULT_SCENARIO_CLASSPATH)) {
            if (in == null) {
                throw new IllegalStateException("Default scenario not found on the classpath at " + DEFAULT_SCENARIO_CLASSPATH);
            }
            return scenarioLoader.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load the default scenario", e);
        }
    }
}
