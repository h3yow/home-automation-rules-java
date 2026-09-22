package com.github.h3yow.homeautomationrulesjava.rule;

/**
 * 规则动作定义：向目标设备下发设置命令。
 *
 * @param targetDeviceId 目标设备
 * @param value 目标取值
 * @param compensation 是否为补偿动作
 */
public record ActionDef(String targetDeviceId, double value, boolean compensation) {
}
