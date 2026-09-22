package com.github.h3yow.homeautomationrulesjava.action;

import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;

import java.util.List;

/**
 * 动作执行器：按序执行动作，失败可区分、重试有界、补偿幂等。
 */
public interface ActionExecutor {

    /**
     * 执行一次规则触发的动作序列。
     *
     * @param triggerId 规则触发实例ID（用于派生动作幂等键）
     * @param actions 有序动作
     * @param compensations 补偿动作（出现失败/条件失效时按序执行）
     * @param maxAttemptsPerAction 单动作最大尝试次数（含首次）
     * @param conditionChecker 每个动作执行前的条件复查器；返回 false 表示条件已失效
     */
    ExecutionReport execute(String triggerId, List<ActionDef> actions,
                            List<ActionDef> compensations, int maxAttemptsPerAction,
                            ConditionChecker conditionChecker);

    /** 条件复查：动作执行前再次确认规则条件仍然成立。 */
    @FunctionalInterface
    interface ConditionChecker {
        boolean stillValid();
    }

    /** 一次规则触发的整体动作执行报告。 */
    record ExecutionReport(ExecutionOutcome outcome, List<ActionResult> steps,
                           List<ActionResult> compensationSteps, String explanation) {
    }

    /** 整体结果。 */
    enum ExecutionOutcome {
        ALL_SUCCESS,
        PARTIAL_FAILURE,
        ALL_FAILED,
        NOT_EXECUTED
    }
}
