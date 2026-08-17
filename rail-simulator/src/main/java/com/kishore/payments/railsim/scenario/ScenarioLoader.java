package com.kishore.payments.railsim.scenario;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses a scenario YAML file into a {@link ScenarioConfig} by hand, against
 * SnakeYAML's plain {@code Map}/{@code List} loading rather than its
 * annotation-driven POJO binding. A scenario rule's shape (a match block
 * plus a handful of optional overrides, most of them absent on any given
 * rule) doesn't map cleanly onto JavaBean-style binding, and this keeps the
 * domain model itself (BehaviorSpec, ScenarioRule, ScenarioConfig) as plain
 * immutable records rather than mutable beans that exist only to satisfy a
 * YAML library.
 */
@Component
public class ScenarioLoader {

    public ScenarioConfig load(InputStream yaml) {
        Map<String, Object> root = asMap(new Yaml().load(yaml));
        return fromMap(root);
    }

    public ScenarioConfig load(byte[] yaml) {
        Map<String, Object> root = asMap(new Yaml().load(new java.io.ByteArrayInputStream(yaml)));
        return fromMap(root);
    }

    private ScenarioConfig fromMap(Map<String, Object> root) {
        String rail = requireString(root, "rail");
        BehaviorSpec defaults = behaviorSpec(requireMap(root, "default"));
        requireFullyPopulated(defaults, rail);

        List<ScenarioRule> rules = new ArrayList<>();
        Object rawRules = root.get("rules");
        if (rawRules instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> ruleMap = asMap(entry);
                MatchCriteria match = matchCriteria(asMap(ruleMap.get("match")));
                rules.add(new ScenarioRule(match, behaviorSpec(ruleMap)));
            }
        }

        String statusCallbackUrl = root.get("statusCallbackUrl") instanceof String s ? s : null;
        String returnCallbackUrl = root.get("returnCallbackUrl") instanceof String s ? s : null;
        StatusQueryBehaviour statusQueryBehaviour = enumValue(root, "statusQueryBehaviour", StatusQueryBehaviour.class);
        Integer statusQueryUnknownCount = toInteger(root.get("statusQueryUnknownCount"));
        Long statusQuerySlowDelayMs = toLong(root.get("statusQuerySlowDelayMs"));
        return new ScenarioConfig(
                rail, defaults, rules, statusCallbackUrl, returnCallbackUrl,
                statusQueryBehaviour, statusQueryUnknownCount, statusQuerySlowDelayMs);
    }

    private static void requireFullyPopulated(BehaviorSpec defaults, String rail) {
        if (defaults.acceptResponse() == null
                || defaults.confirmation() == null
                || defaults.acceptDelayMs() == null
                || defaults.confirmationDelayMs() == null) {
            throw new IllegalArgumentException(
                    "Scenario for rail " + rail + " has an incomplete default: block -- acceptResponse, "
                            + "confirmation, acceptDelayMs and confirmationDelayMs must all be set, since rules "
                            + "only override a subset and fall back to these");
        }
    }

    private static BehaviorSpec behaviorSpec(Map<String, Object> map) {
        return new BehaviorSpec(
                enumValue(map, "acceptResponse", AcceptResponse.class),
                toLong(map.get("acceptDelayMs")),
                enumValue(map, "confirmation", ConfirmationType.class),
                toLong(map.get("confirmationDelayMs")),
                map.get("rejectReasonCode") instanceof String s ? s : null,
                toLong(map.get("timeoutHoldMs")),
                map.get("recordBeforeTimeout") instanceof Boolean b ? b : null,
                toInteger(map.get("errorCount")),
                toLong(map.get("returnDelayMs")),
                map.get("returnReasonCode") instanceof String s ? s : null);
    }

    private static MatchCriteria matchCriteria(Map<String, Object> map) {
        return new MatchCriteria(
                toBigDecimal(map.get("amountGreaterThan")),
                toBigDecimal(map.get("amountLessThan")),
                map.get("currency") instanceof String s ? s : null,
                map.get("creditorAccountEndsWith") instanceof String s ? s : null,
                map.get("debtorAgentBic") instanceof String s ? s : null,
                toInteger(map.get("everyNth")),
                toInteger(map.get("deliveryAttemptAtLeast")),
                map.get("always") instanceof Boolean b ? b : null);
    }

    private static <E extends Enum<E>> E enumValue(Map<String, Object> map, String key, Class<E> type) {
        Object value = map.get(key);
        return value instanceof String s ? Enum.valueOf(type, s) : null;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString());
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Scenario is missing required field: " + key);
        }
        return s;
    }

    private static Map<String, Object> requireMap(Map<String, Object> map, String key) {
        return asMap(map.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a YAML mapping, got: " + value);
        }
        return (Map<String, Object>) value;
    }
}
