package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 设备已确认状态的不可变快照。
 * 每个属性独立携带 lastSeq（最后确认的上报序号）与 confirmedAt（确认时间戳），
 * 使得乱序/迟到上报可以按属性做单调判定，已确认状态不会回退。
 */
public final class DeviceState {

    /**
     * 单个属性的已确认值及其确认元数据。
     *
     * @param lastSeq     上报序号空间内的高水位（仅在来源为上报时有意义；动作回写不占用上报序号）
     * @param reportEpoch 该属性“已确认上报”的纪元计数：每次被上报确认推进就 +1，
     *                    动作下发/回滚回写不改变它。回滚时据此判断触发后是否有新的上报已确认，
     *                    避免一次回滚把已经确认的新状态盖回旧值。
     */
    public record ConfirmedProperty(Object value, long lastSeq, long confirmedAt, long reportEpoch) {
    }

    private final String deviceId;
    private final Map<String, ConfirmedProperty> properties;

    public DeviceState(String deviceId, Map<String, ConfirmedProperty> properties) {
        this.deviceId = Objects.requireNonNull(deviceId);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties)));
    }

    public String deviceId() {
        return deviceId;
    }

    public Map<String, ConfirmedProperty> properties() {
        return properties;
    }

    public ConfirmedProperty get(String property) {
        return properties.get(property);
    }

    public Object valueOf(String property) {
        ConfirmedProperty cp = properties.get(property);
        return cp == null ? null : cp.value();
    }
}
