package com.github.h3yow.homeautomationrulesjava.action;

/** 可区分的动作失败原因。 */
public enum FailureKind {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    CONDITION_INVALID,
    UNREGISTERED_TARGET,
    INVALID_TARGET_VALUE
}
