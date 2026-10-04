package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 失败回滚（补偿）的最终状态。用于让“查到的设备状态 / 动作执行记录 / 最终结论”三者严格对齐：
 * 只有真正恢复成功才允许表现为“已恢复”，其余任何情况都不能说已经回滚。
 */
public enum CompensationStatus {

    /** 该动作不需要补偿（未成功执行、或整条序列全部成功）。 */
    NOT_NEEDED,

    /** 已成功恢复为触发前原值：物理侧已回滚，查询侧设备状态同步为触发前原值（含恢复“属性缺失”）。 */
    RESTORED,

    /** 触发之后已有新的设备上报被确认：回滚有意跳过，不得覆盖那个更新的已确认值，不属于失败。 */
    SUPERSEDED_BY_NEW_REPORT,

    /** 回滚命令下发失败：查询侧仍停留在失败前被改成的值，需要人工介入。 */
    FAILED,

    /** 拿不到触发前原值（如触发前目标设备没有任何已确认快照），无法回滚，需要人工介入。 */
    ORIGINAL_UNAVAILABLE;

    /** 兼容旧判定：只有真正恢复成功才算“已补偿”。 */
    public boolean isRestored() {
        return this == RESTORED;
    }

    /** 回滚动作已经尝试/处理过（不论成功与否），用于与 NOT_NEEDED 区分。 */
    public boolean attempted() {
        return this != NOT_NEEDED;
    }
}
