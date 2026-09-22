package com.github.h3yow.homeautomationrulesjava.action;

/**
 * 单步动作结果（可追溯记录）。
 *
 * @param seq 动作序号
 * @param deviceId 目标设备
 * @param value 目标取值
 * @param status 最终状态
 * @param attempts 实际执行尝试次数
 * @param failureKind 失败原因（成功为 null）
 * @param idempotencyKey 幂等键
 */
public record ActionResult(int seq, String deviceId, double value, ActionStatus status,
                           int attempts, FailureKind failureKind, String idempotencyKey) {
}
