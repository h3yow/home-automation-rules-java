package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 规则“出现 false→true 边沿但未实际触发”的可区分原因。
 * 用于在事件判定结果与追溯轨迹中区分：
 * - 条件没到（根本没有边沿，不产生记录）；
 * - 条件持续满足（STILL_SATISFIED，边沿状态未变化）；
 * - 边沿发生但被冷却窗口挡下（COOLDOWN_ACTIVE）。
 */
public enum RuleSkipReason {
    /** 条件持续满足：边沿状态仍为 true，非新的 false→true。 */
    STILL_SATISFIED,
    /** false→true 边沿发生在上次触发后的最短触发间隔（冷却）内，本次触发被挡下。 */
    COOLDOWN_ACTIVE
}
