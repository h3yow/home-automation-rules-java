package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.action.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.device.StateStore;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.ConditionEvaluator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.store.AuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryRuleStore;
import com.github.h3yow.homeautomationrulesjava.store.RuleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 默认规则引擎。
 *
 * 处理流程：
 *  1. 事件交 StateStore 确认（去重/乱序防护在存储层完成）；
 *  2. 只有 ACCEPTED（状态被真正确认推进）才评估规则，DUPLICATE/REJECTED 不触发；
 *  3. 触发幂等：以 (ruleId, eventId) 为键，用 CompletableFuture 占位 + putIfAbsent，
 *     并发的同一事件只有一个线程执行动作序列一次，其余线程等待同一结果（deduplicated=true），
 *     不重复下发、不遗漏；
 *  4. 每个动作执行前复查条件快照，捕获条件失效；
 *  5. 输入事件、判定依据、执行结果写入审计日志并打印，结论可解释。
 */
@SuppressWarnings("unused")
@Component
public class DefaultRuleEngine implements RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRuleEngine.class);

    private final StateStore stateStore;
    private final InMemoryRuleStore ruleStore;
    private final ActionExecutor actionExecutor;
    private final InMemoryAuditLog auditLog;
    private final int maxAttempts;

    /** (ruleId|eventId) -> 触发结果 future；保证每个组合的动作序列只执行一次。 */
    private final Map<String, CompletableFuture<TriggerOutcome>> firedTriggers
            = new ConcurrentHashMap<>();

    public DefaultRuleEngine(StateStore stateStore, InMemoryRuleStore ruleStore,
                             ActionExecutor actionExecutor, InMemoryAuditLog auditLog,
                             @Value("${rules.max-attempts:3}") int maxAttempts) {
        this.stateStore = stateStore;
        this.ruleStore = ruleStore;
        this.actionExecutor = actionExecutor;
        this.auditLog = auditLog;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public EngineResult handle(EventReport event) {
        EventReceipt receipt = stateStore.accept(event);

        String receiptBasis = switch (receipt.status()) {
            case ACCEPTED -> "事件被确认，状态推进到 version=" + receipt.stateVersion();
            case DUPLICATE -> "eventId=" + event.eventId() + " 已处理过，幂等忽略，不触发规则";
            case REJECTED -> "事件被拒绝，原因=" + receipt.reason();
        };
        auditLog.appendEvent(new AuditLog.EventRecord(
                auditLog.nextEventSeq(), event, receipt, receiptBasis));
        log.info("[事件输入] eventId={} device={} value={} eventTime={} -> 受理={} 依据: {}",
                event.eventId(), event.deviceId(), event.value(), event.eventTime(),
                receipt.status(), receiptBasis);

        if (receipt.status() != ReceiptStatus.ACCEPTED) {
            return new EngineResult(receipt, List.of());
        }

        List<TriggerOutcome> outcomes = new ArrayList<>();
        for (RuleEntry entry : ruleStore.enabledEntries()) {
            Rule rule = entry.rule();
            if (!rule.triggerDeviceId().equals(event.deviceId())) {
                continue;
            }
            outcomes.add(evaluateTrigger(rule, entry.version(), event));
        }
        return new EngineResult(receipt, List.copyOf(outcomes));
    }

    private TriggerOutcome evaluateTrigger(Rule rule, long ruleVersion, EventReport event) {
        String dedupeKey = rule.id() + "|" + event.eventId();
        String triggerId = rule.id() + ":" + event.eventId();

        CompletableFuture<TriggerOutcome> mine = new CompletableFuture<>();
        CompletableFuture<TriggerOutcome> existing = firedTriggers.putIfAbsent(dedupeKey, mine);
        if (existing == null) {
            // 唯一进入者：真正执行一次
            TriggerOutcome result;
            try {
                result = doTrigger(rule, ruleVersion, event, triggerId);
            } catch (RuntimeException e) {
                firedTriggers.remove(dedupeKey, mine);
                mine.completeExceptionally(e);
                throw e;
            }
            mine.complete(result);
            return result;
        }
        // 并发重复者：等待同一结果，标记 deduplicated，不重复下发
        TriggerOutcome shared = existing.join();
        TriggerOutcome dupView = new TriggerOutcome(
                shared.ruleId(), shared.triggerId(), shared.matched(), true, shared.report());
        log.info("[规则判定] rule={} eventId={} 为并发重复事件，复用首次触发结果（不重复下发）",
                rule.id(), event.eventId());
        return dupView;
    }

    private TriggerOutcome doTrigger(Rule rule, long ruleVersion,
                                     EventReport event, String triggerId) {
        Map<String, DeviceState> snapshot = freshSnapshot();
        boolean matched = ConditionEvaluator.evaluate(rule.conditions(), snapshot);
        String basis = explainBasis(rule, snapshot, matched);

        ActionExecutor.ExecutionReport report = null;
        if (matched) {
            report = actionExecutor.execute(
                    triggerId, rule.actions(), rule.compensations(), maxAttempts,
                    () -> ConditionEvaluator.evaluate(rule.conditions(), freshSnapshot()));
            basis = basis + "；动作结果=" + report.outcome() + "（" + report.explanation() + "）";
        }
        TriggerOutcome result = new TriggerOutcome(
                rule.id(), triggerId, matched, false, report);
        auditLog.appendTrigger(new AuditLog.TriggerRecord(
                auditLog.nextTriggerSeq(), rule.id(), triggerId,
                event.eventId(), matched, basis, report));
        log.info("[规则判定] rule={} v{} eventId={} matched={} 依据: {}",
                rule.id(), ruleVersion, event.eventId(), matched, basis);
        return result;
    }

    private Map<String, DeviceState> freshSnapshot() {
        return stateStore.snapshot().stream()
                .collect(Collectors.toMap(DeviceState::deviceId, Function.identity()));
    }

    private String explainBasis(Rule rule, Map<String, DeviceState> snapshot, boolean matched) {
        StringBuilder sb = new StringBuilder("触发设备=").append(rule.triggerDeviceId())
                .append("，条件数=").append(rule.conditions().size());
        for (Condition c : rule.conditions()) {
            sb.append("；[").append(ConditionEvaluator.explain(c, snapshot)).append(']');
        }
        sb.append(" => 匹配=").append(matched);
        return sb.toString();
    }

    /** 测试辅助：清空触发幂等记录。 */
    public void clearTriggerHistory() {
        firedTriggers.clear();
    }

    /** 已触发（去重后）的规则-事件组合数。 */
    public int firedCount() {
        return firedTriggers.size();
    }
}
