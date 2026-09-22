package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;

/**
 * 单个动作的执行记录（可追溯）。
 *
 * @param idempotencyKey 全局副作用幂等键 = ruleId:fireNo:actionDefId
 * @param action         动作定义快照
 * @param status         最终状态
 * @param attempts       实际下发尝试次数（幂等去重后的重复请求不计入）
 * @param failureReason  失败时的可区分原因；成功/跳过时为 null
 * @param compensated    失败后补偿是否成功执行
 * @param errors         每次尝试的错误说明，保证链路可解释
 */
public record ActionExecutionRecord(
        String idempotencyKey,
        Action action,
        ActionStatus status,
        int attempts,
        ActionFailureReason failureReason,
        boolean compensated,
        List<String> errors
) {
    public boolean succeeded() {
        return status == ActionStatus.SUCCESS;
    }
}
