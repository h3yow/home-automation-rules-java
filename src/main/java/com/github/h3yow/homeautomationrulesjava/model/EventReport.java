package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 设备状态上报事件。
 *
 * @param eventId 调用方提供的去重ID（同ID事件只生效一次）
 * @param deviceId 设备ID
 * @param value 上报取值
 * @param eventTime 事件产生时间（epoch ms），用于乱序/迟到判定
 */
public record EventReport(String eventId, String deviceId, double value, long eventTime) {
}
