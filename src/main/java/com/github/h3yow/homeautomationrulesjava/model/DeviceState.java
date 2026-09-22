package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 已确认的设备状态。
 *
 * @param deviceId 设备ID
 * @param value 已确认取值
 * @param version 单调递增版本号，每次实际状态确认 +1
 * @param eventTime 产生该状态的事件时间（epoch ms）
 * @param acceptedAt 受理时间（epoch ms）
 */
public record DeviceState(String deviceId, double value, long version, long eventTime, long acceptedAt) {
}
