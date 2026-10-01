package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;

/**
 * 一次规则评估的整体结论。
 *
 * 触发时 fired=true、skipReason=null、records 为动作执行记录；
 * 未触发时 fired=false，skipReason 给出可区分原因：
 * - CONDITION_NOT_SATISFIED：条件当前不满足；
 * - STILL_SATISFIED：条件持续满足（非边沿），本就不该重复触发；
 * - COOLDOWN_ACTIVE：发生了 false→true 边沿，但落在上一次成功触发后的冷却窗口内被挡下。
 *
 * @param ruleId         规则 id
 * @param ruleVersion    评估时规则版本
 * @param fireNo         该规则的单调触发编号（第几次触发），未真正触发时为 0
 * @param fired          是否实际触发并执行动作
 * @param deduped        是否被并发幂等去重（同一事件并发到达只触发一次，后续记为 deduped）
 * @param skipReason     未触发原因；触发时为 null
 * @param cooldownMillis 评估时规则配置的最短触发间隔（0 表示未配置冷却）
 * @param retryAtMillis  被冷却挡下时，下一次可触发边沿最早出现的处理时刻（左闭右开窗口终点）；否则为 null
 * @param records        每个动作的执行记录（按规则动作顺序；未触发时为空）
 * @param summary        可解释的整体结论
 */
public record RuleFireOutcome(
        String ruleId,
        long ruleVersion,
        long fireNo,
        boolean fired,
        boolean deduped,
        RuleSkipReason skipReason,
        long cooldownMillis,
        Long retryAtMillis,
        List<ActionExecutionRecord> records,
        String summary
) {

    public RuleFireOutcome(String ruleId, long ruleVersion, long fireNo, boolean fired, boolean deduped,
                           List<ActionExecutionRecord> records, String summary) {
        this(ruleId, ruleVersion, fireNo, fired, deduped, null, 0L, null, records, summary);
    }

    /** 构造一个“未触发”结论（不领取 fireNo、不执行动作）。 */
    public static RuleFireOutcome skipped(String ruleId, long ruleVersion, RuleSkipReason reason,
                                          long cooldownMillis, Long retryAtMillis, String summary) {
        return new RuleFireOutcome(ruleId, ruleVersion, 0L, false, false,
                reason, cooldownMillis, retryAtMillis, List.of(), summary);
    }

    /** 构造一个“已触发”结论（携带当时生效的冷却配置，供追溯）。 */
    public static RuleFireOutcome fired(String ruleId, long ruleVersion, long fireNo,
                                        long cooldownMillis, List<ActionExecutionRecord> records,
                                        String summary) {
        return new RuleFireOutcome(ruleId, ruleVersion, fireNo, true, false,
                null, cooldownMillis, null, records, summary);
    }

    public boolean fullySuccessful() {
        return fired && records.stream().allMatch(ActionExecutionRecord::succeeded);
    }

    public boolean partiallyFailed() {
        return fired && records.stream().anyMatch(r -> !r.succeeded());
    }

    /** 是否被冷却挡下（发生过边沿但在冷却窗口内）。 */
    public boolean suppressedByCooldown() {
        return skipReason == RuleSkipReason.COOLDOWN_ACTIVE;
    }
}
