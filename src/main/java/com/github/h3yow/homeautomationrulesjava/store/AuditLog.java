package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.action.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.action.ActionResult;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;

import java.util.List;

/** 审计/追溯日志（仅内存，启动后可查）。 */
public interface AuditLog {

    /** 事件受理记录。 */
    record EventRecord(long seq, EventReport event, EventReceipt receipt, String basis) {
    }

    /** 规则评估/触发记录。 */
    record TriggerRecord(long seq, String ruleId, String triggerId, String eventId,
                         boolean matched, String basis,
                         ActionExecutor.ExecutionReport report) {
    }

    void appendEvent(EventRecord record);

    void appendTrigger(TriggerRecord record);

    List<EventRecord> events();

    List<TriggerRecord> triggers();
}
