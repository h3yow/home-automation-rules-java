package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.action.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.device.StateStore;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.store.AuditLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;

/**
 * 规则引擎：受理事件 -> 确认状态 -> 匹配规则（幂等）-> 执行动作 -> 记录审计。
 */
public interface RuleEngine {
    /** 处理一个上报事件，返回收据及可能的触发执行结果。 */
    EngineResult handle(EventReport event);

    /** 一次事件处理的完整结果。 */
    record EngineResult(EventReceipt receipt, java.util.List<TriggerOutcome> triggers) {
    }

    /** 单条规则的触发结果：未匹配 / 已去重跳过 / 已执行。 */
    record TriggerOutcome(String ruleId, String triggerId, boolean matched,
                          boolean deduplicated, ActionExecutor.ExecutionReport report) {
    }
}
