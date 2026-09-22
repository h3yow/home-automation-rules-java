package com.github.h3yow.homeautomationrulesjava.model;

/** 上报事件进入系统后的处理大类。 */
public enum IngestOutcomeType {
    /** 事件通过校验且序号更新，已确认状态被推进。 */
    ACCEPTED,
    /** 事件非法（设备未注册/取值非法/结构缺失），被拒绝且不产生任何副作用。 */
    REJECTED,
    /** 事件本身合法，但属于重复投递或乱序迟到，被幂等忽略，已确认状态不回退。 */
    IGNORED
}
