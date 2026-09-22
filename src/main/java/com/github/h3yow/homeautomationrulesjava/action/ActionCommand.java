package com.github.h3yow.homeautomationrulesjava.action;

/**
 * 下发给设备的命令。
 *
 * @param idempotencyKey 幂等键：同一键重复执行只生效一次
 * @param deviceId 目标设备
 * @param value 目标取值
 * @param attempt 尝试序号（1 起始）
 */
public record ActionCommand(String idempotencyKey, String deviceId, double value, int attempt) {
}
