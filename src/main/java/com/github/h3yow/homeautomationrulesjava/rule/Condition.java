package com.github.h3yow.homeautomationrulesjava.rule;

/**
 * 规则条件：某设备当前状态与阈值比较。
 *
 * @param deviceId 被观察设备
 * @param operator 比较算子
 * @param threshold 阈值
 */
public record Condition(String deviceId, Operator operator, double threshold) {
}
