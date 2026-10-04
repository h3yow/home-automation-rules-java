package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;

/**
 * 单个动作的执行记录（可追溯）。
 *
 * @param idempotencyKey     全局副作用幂等键 = ruleId:fireNo:actionDefId
 * @param action             动作定义快照
 * @param status             最终状态
 * @param attempts           实际下发尝试次数（幂等去重后的重复请求不计入）
 * @param failureReason      失败时的可区分原因；成功/跳过时为 null
 * @param compensationStatus 失败回滚（补偿）的最终状态；未涉及补偿时为 NOT_NEEDED。
 *                           只有 RESTORED 才表示设备状态真正恢复为触发前原值，
 *                           FAILED / ORIGINAL_UNAVAILABLE 表示未恢复、需人工介入，
 *                           SUPERSEDED_BY_NEW_REPORT 表示回滚有意让位于更新的已确认上报。
 * @param errors             每次尝试的错误说明，保证链路可解释
 */
public record ActionExecutionRecord(
        String idempotencyKey,
        Action action,
        ActionStatus status,
        int attempts,
        ActionFailureReason failureReason,
        CompensationStatus compensationStatus,
        List<String> errors
) {
    public boolean succeeded() {
        return status == ActionStatus.SUCCESS;
    }

    /** 兼容旧判定：仅当确实恢复为触发前原值时为 true。 */
    public boolean compensated() {
        return compensationStatus != null && compensationStatus.isRestored();
    }
}
