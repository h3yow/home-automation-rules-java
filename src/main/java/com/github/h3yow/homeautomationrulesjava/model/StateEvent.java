package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Objects;

/**
 * 设备状态上报事件。
 *
 * @param deviceId  目标设备
 * @param property  发生变化的属性名
 * @param rawValue  上报的原始值（经 PropertySpec.normalize 校验后才可信）
 * @param seq       上报端为该设备该属性单调递增的序号，用于乱序/迟到判定
 * @param eventId   客户端提供的幂等键（同一物理上报的重复投递必须复用同一 eventId）
 * @param eventTime 事件产生时间（毫秒），可早于到达时间（迟到事件）
 */
public record StateEvent(
        String deviceId,
        String property,
        Object rawValue,
        Long seq,
        String eventId,
        Long eventTime
) {
    public StateEvent {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(seq, "seq");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
