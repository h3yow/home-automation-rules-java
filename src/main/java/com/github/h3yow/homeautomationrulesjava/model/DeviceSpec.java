package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 设备注册信息（不可变）。
 *
 * @param deviceId 设备ID
 * @param type 设备类型
 * @param minValue 合法取值下限（含）
 * @param maxValue 合法取值上限（含）
 */
public record DeviceSpec(String deviceId, DeviceType type, double minValue, double maxValue) {
    public DeviceSpec {
    }
}
