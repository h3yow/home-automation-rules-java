package com.github.h3yow.homeautomationrulesjava.rule;

import java.util.List;

/**
 * 自动化规则（配置不可变；启停与版本由 Registry 管理）。
 *
 * @param id 规则ID
 * @param name 名称
 * @param triggerDeviceId 触发设备
 * @param triggerOn 触发方式
 * @param conditions 全部满足（AND）才执行
 * @param actions 有序动作序列
 * @param compensations 失败时按序执行的补偿动作
 */
public record Rule(String id, String name, String triggerDeviceId, TriggerOn triggerOn,
                   List<Condition> conditions, List<ActionDef> actions,
                   List<ActionDef> compensations) {
}
