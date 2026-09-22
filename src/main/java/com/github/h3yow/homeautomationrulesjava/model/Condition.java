package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Objects;

/**
 * 规则条件：当 deviceId.property 与 expected 满足 operator 时条件成立。
 * expected 的类型由目标设备属性规格在校验/规范化阶段确定。
 */
public record Condition(
        String deviceId,
        String property,
        Operator operator,
        Object expected
) {
    public Condition {
        Objects.requireNonNull(deviceId, "condition.deviceId");
        Objects.requireNonNull(property, "condition.property");
        Objects.requireNonNull(operator, "condition.operator");
        Objects.requireNonNull(expected, "condition.expected");
    }
}
