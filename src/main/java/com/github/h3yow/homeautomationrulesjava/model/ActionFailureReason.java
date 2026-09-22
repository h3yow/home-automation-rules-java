package com.github.h3yow.homeautomationrulesjava.model;

/** 动作执行失败时可区分的异常链路原因。 */
public enum ActionFailureReason {
    /** 目标设备未注册 / 不可达，命令无法送达。 */
    DEVICE_UNREACHABLE,
    /** 命令发出后执行超时。 */
    EXECUTION_TIMEOUT,
    /** 下发瞬间条件已失效（条件复核失败），动作被跳过。 */
    CONDITION_RECHECK_FAILED,
    /** 动作本身非法（设备/属性不存在或取值不合法），不重试。 */
    INVALID_ACTION
}
