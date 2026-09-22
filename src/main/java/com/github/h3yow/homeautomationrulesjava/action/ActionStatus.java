package com.github.h3yow.homeautomationrulesjava.action;

/**
 * 单步动作的执行状态。
 * SUCCESS：成功（可能经历过重试，重试次数看 attempts）；
 * FAILED_FINAL：重试耗尽仍失败；
 * SKIPPED_CONDITION_INVALID：执行前复查条件已失效，动作未下发；
 * COMPENSATED：补偿动作执行成功。
 */
public enum ActionStatus {
    SUCCESS,
    FAILED_FINAL,
    SKIPPED_CONDITION_INVALID,
    COMPENSATED
}
