package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Objects;

/**
 * 规则动作：把 targetDeviceId.property 设置为 value（经设备属性规格校验）。
 * actionDefId 是规则内动作的稳定标识，配合规则触发实例号构成全局副作用幂等键，
 * 保证重试/补偿不会对同一动作产生重复副作用。
 */
public record Action(
        String actionDefId,
        String targetDeviceId,
        String property,
        Object value
) {
    public Action {
        Objects.requireNonNull(actionDefId, "actionDefId");
        Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(value, "value");
    }
}
