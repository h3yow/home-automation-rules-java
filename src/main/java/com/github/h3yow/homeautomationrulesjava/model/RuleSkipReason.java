package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 规则在一次已确认事件评估中“条件满足但没有触发动作”的可区分原因。
 * 用于事件判定结果与追溯轨迹，便于一眼区分“条件一直在”和“被冷却挡住”。
 */
public enum RuleSkipReason {

    /** 条件组持续满足（非边沿），按边沿语义本就不应重复触发。 */
    STILL_SATISFIED,

    /** 出现了 false→true 边沿，但距上次成功触发仍在最短触发间隔（冷却）内，本次被挡下。 */
    COOLDOWN_ACTIVE
}
