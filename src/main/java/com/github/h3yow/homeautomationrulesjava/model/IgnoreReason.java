package com.github.h3yow.homeautomationrulesjava.model;

/** IGNORED 结果的可区分原因。 */
public enum IgnoreReason {
    /** 相同 eventId 已处理过（重复投递）。 */
    DUPLICATE_EVENT,
    /** seq 小于该属性已确认序号（乱序/迟到上报）。 */
    OUTDATED_SEQ,
    /** seq 与已确认序号相同但来自不同 eventId（同一序号的重复上报）。 */
    STALE_SEQ
}
