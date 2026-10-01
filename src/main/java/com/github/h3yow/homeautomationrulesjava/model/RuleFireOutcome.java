package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;

/**
 * 一次规则评估的整体结果。
 *
 * @param ruleId            规则 id
 * @param ruleVersion       评估时规则版本
 * @param fireNo            该规则的单调触发编号（第几次实际触发）；未实际触发时为 0
 * @param fired             是否实际触发并（尝试）执行了动作序列
 * @param deduped           是否被并发幂等去重（同一事件并发到达只触发一次，后续记为 deduped）
 * @param skipReason        出现边沿/处于满足态但未触发时的可区分原因；实际触发时为 null
 * @param cooldownMillis    规则当前配置的最短触发间隔（毫秒），未启用为 null
 * @param cooldownRemaining 被冷却挡下时，距冷却结束还剩的毫秒数；其它情况为 null
 * @param records           每个动作的执行记录（按规则动作顺序）
 * @param summary           可解释的整体结论
 */
public record RuleFireOutcome(
        String ruleId,
        long ruleVersion,
        long fireNo,
        boolean fired,
        boolean deduped,
        RuleSkipReason skipReason,
        Long cooldownMillis,
        Long cooldownRemaining,
        List<ActionExecutionRecord> records,
        String summary
) {

    public static RuleFireOutcome fired(String ruleId, long ruleVersion, long fireNo,
                                        List<ActionExecutionRecord> records, String summary) {
        return new RuleFireOutcome(ruleId, ruleVersion, fireNo, true, false,
                null, null, null, records, summary);
    }

    /** 条件持续满足（非边沿）或边沿被冷却挡下：未触发，无动作、无 fireNo。 */
    public static RuleFireOutcome skipped(String ruleId, long ruleVersion, RuleSkipReason reason,
                                          Long cooldownMillis, Long cooldownRemaining, String summary) {
        return new RuleFireOutcome(ruleId, ruleVersion, 0L, false, false,
                reason, cooldownMillis, cooldownRemaining, List.of(), summary);
    }

    public boolean fullySuccessful() {
        return fired && records.stream().allMatch(ActionExecutionRecord::succeeded);
    }

    public boolean partiallyFailed() {
        return fired && records.stream().anyMatch(r -> !r.succeeded());
    }

    public boolean cooldownBlocked() {
        return skipReason == RuleSkipReason.COOLDOWN_ACTIVE;
    }
}
