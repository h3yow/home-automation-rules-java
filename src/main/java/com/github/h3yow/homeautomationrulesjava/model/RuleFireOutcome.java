package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;

/**
 * 一次规则触发的整体执行结果。
 *
 * @param ruleId         规则 id
 * @param ruleVersion    触发时规则版本
 * @param fireNo         该规则的单调触发编号（第几次触发），也是并发去重依据
 * @param fired          是否实际触发（边沿判定为 false 时不产生动作）
 * @param deduped        是否被并发幂等去重（同一事件并发到达只触发一次，后续记为 deduped）
 * @param records        每个动作的执行记录（按规则动作顺序）
 * @param summary        可解释的整体结论
 */
public record RuleFireOutcome(
        String ruleId,
        long ruleVersion,
        long fireNo,
        boolean fired,
        boolean deduped,
        List<ActionExecutionRecord> records,
        String summary
) {

    public boolean fullySuccessful() {
        return fired && records.stream().allMatch(ActionExecutionRecord::succeeded);
    }

    public boolean partiallyFailed() {
        return fired && records.stream().anyMatch(r -> !r.succeeded());
    }
}
