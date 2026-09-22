package com.github.h3yow.homeautomationrulesjava.rule;

import com.github.h3yow.homeautomationrulesjava.model.DeviceState;

import java.util.List;
import java.util.Map;

/** 条件评估器：所有条件对同一状态快照同时成立（AND）才返回 true。 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * @return 全部条件成立返回 true；任一条件引用设备无状态或不成立，返回 false。
     */
    public static boolean evaluate(List<Condition> conditions, Map<String, DeviceState> snapshot) {
        if (conditions == null || conditions.isEmpty()) {
            // 无条件规则：由触发事件本身驱动
            return true;
        }
        for (Condition c : conditions) {
            DeviceState state = snapshot.get(c.deviceId());
            if (state == null) {
                return false;
            }
            if (!compare(state.value(), c.operator(), c.threshold())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 评估单个条件，并给出可解释依据。
     *
     * @return 依据描述
     */
    public static String explain(Condition c, Map<String, DeviceState> snapshot) {
        DeviceState state = snapshot.get(c.deviceId());
        if (state == null) {
            return c.deviceId() + " 无已确认状态，条件(" + c.operator() + " " + c.threshold() + ")不成立";
        }
        boolean ok = compare(state.value(), c.operator(), c.threshold());
        return c.deviceId() + "=" + state.value() + "(v" + state.version() + ") "
                + c.operator() + " " + c.threshold() + " => " + ok;
    }

    static boolean compare(double actual, Operator operator, double threshold) {
        return switch (operator) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case EQ -> actual == threshold;
            case NE -> actual != threshold;
        };
    }
}
