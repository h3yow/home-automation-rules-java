package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件判定器：对规则的全部条件做 AND 求值，并产出逐条件的判定说明。
 * 判定基于 StateStore 的已确认状态快照，因此规则永远只对“已确认”的状态做决定。
 */
@Component
public class ConditionEvaluator {

    /** 单条件求值结果。 */
    public record ClauseResult(Condition condition, boolean matched, String detail) {
    }

    /** 条件组求值结果。 */
    public record Evaluation(boolean allMatched, List<ClauseResult> clauses) {
        public String describe() {
            List<String> parts = new ArrayList<>();
            for (ClauseResult c : clauses) {
                parts.add((c.matched() ? "[✓] " : "[✗] ") + c.detail());
            }
            return "AND(" + (allMatched ? "all matched" : "not all matched") + ") " + String.join("; ", parts);
        }
    }

    private final StateStore stateStore;
    private final DeviceRegistry deviceRegistry;

    public ConditionEvaluator(StateStore stateStore, DeviceRegistry deviceRegistry) {
        this.stateStore = stateStore;
        this.deviceRegistry = deviceRegistry;
    }

    public Evaluation evaluate(List<Condition> conditions) {
        List<ClauseResult> results = new ArrayList<>();
        boolean all = true;
        for (Condition c : conditions) {
            ClauseResult r = evaluateOne(c);
            results.add(r);
            all = all && r.matched();
        }
        return new Evaluation(all, List.copyOf(results));
    }

    /** 同时给出每个条件涉及的设备状态快照（供动作前“条件复核”使用）。 */
    public Map<String, DeviceState> snapshotsFor(List<Condition> conditions) {
        java.util.HashMap<String, DeviceState> out = new java.util.HashMap<>();
        for (Condition c : conditions) {
            out.computeIfAbsent(c.deviceId(), stateStore::snapshotOf);
        }
        return Map.copyOf(out);
    }

    /** 取单台设备当前已确认状态快照（补偿取“触发前原值”时对动作目标设备使用）。 */
    public DeviceState snapshotOf(String deviceId) {
        return stateStore.snapshotOf(deviceId);
    }

    private ClauseResult evaluateOne(Condition c) {
        DeviceState state = stateStore.snapshotOf(c.deviceId());
        Object actual = state.valueOf(c.property());
        PropertySpec spec = deviceRegistry.exists(c.deviceId())
                ? deviceRegistry.find(c.deviceId()).specOf(c.property())
                : null;
        if (actual == null) {
            return new ClauseResult(c, false,
                    c.deviceId() + "." + c.property() + " has no confirmed value yet");
        }
        boolean matched = compare(spec, actual, c.operator(), c.expected());
        String detail = c.deviceId() + "." + c.property()
                + " actual=" + actual + " " + c.operator() + " expected=" + c.expected()
                + " => " + matched;
        return new ClauseResult(c, matched, detail);
    }

    /**
     * 比较实际值与期望值。若能找到属性规格，则按规格类型规范化后比较，
     * 避免 "20" 与 20 这类类型差异造成误判。
     */
    public boolean compare(PropertySpec spec, Object actual, Operator op, Object expected) {
        Object a = actual;
        Object e = expected;
        if (spec != null) {
            try {
                a = spec.normalize(actual);
                e = spec.normalize(expected);
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        if (a instanceof Number an && e instanceof Number en) {
            double x = an.doubleValue();
            double y = en.doubleValue();
            return switch (op) {
                case EQ -> x == y;
                case NE -> x != y;
                case GT -> x > y;
                case GE -> x >= y;
                case LT -> x < y;
                case LE -> x <= y;
            };
        }
        int cmp = a.toString().compareTo(e.toString());
        return switch (op) {
            case EQ -> a.equals(e) || a.toString().equals(e.toString());
            case NE -> !(a.equals(e) || a.toString().equals(e.toString()));
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
        };
    }
}
