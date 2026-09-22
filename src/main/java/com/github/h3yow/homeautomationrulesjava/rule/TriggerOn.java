package com.github.h3yow.homeautomationrulesjava.rule;

/**
 * 规则触发方式。
 * STATE_CHANGE: 触发设备状态被确认（前进）时评估
 * TICK: 任意状态确认后都参与评估（由条件本身决定）
 */
public enum TriggerOn {
    STATE_CHANGE,
    TICK
}
