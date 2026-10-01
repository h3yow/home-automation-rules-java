package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.ActionExecutionRecord;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地执行日志：按到达顺序保存全部判定与执行轨迹，便于追溯与断言。
 * CopyOnWriteArrayList 保证并发追加与遍历读取互不干扰。
 */
@Component
public class ExecutionLog {

    public record EventTrace(
            long timestamp,
            String deviceId,
            String property,
            Long seq,
            String eventId,
            IngestResult result
    ) {
    }

    public record FireTrace(
            long timestamp,
            String ruleId,
            RuleFireOutcome outcome
    ) {
    }

    private final CopyOnWriteArrayList<EventTrace> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<FireTrace> fires = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ActionExecutionRecord> actions = new CopyOnWriteArrayList<>();

    public void appendEvent(String deviceId, String property, Long seq, String eventId, IngestResult result) {
        events.add(new EventTrace(System.currentTimeMillis(), deviceId, property, seq, eventId, result));
    }

    public void appendFire(String ruleId, RuleFireOutcome outcome) {
        fires.add(new FireTrace(System.currentTimeMillis(), ruleId, outcome));
    }

    public void appendAction(ActionExecutionRecord record) {
        actions.add(record);
    }

    /**
     * 补偿完成后回填动作记录：按幂等键把轨迹中的旧记录替换为“已补偿”版本，
     * 保证追溯记录与规则触发最终结论一致（不能出现结论说已回滚、记录却没跟上）。
     */
    public void replaceAction(ActionExecutionRecord record) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).idempotencyKey().equals(record.idempotencyKey())) {
                actions.set(i, record);
                return;
            }
        }
        // 理论上不会发生：补偿的记录必然此前已追加；兜底追加以免信息丢失。
        actions.add(record);
    }

    public void clear() {
        events.clear();
        fires.clear();
        actions.clear();
    }

    public List<EventTrace> eventTraces() {
        return List.copyOf(events);
    }

    public List<FireTrace> fireTraces() {
        return List.copyOf(fires);
    }

    public List<ActionExecutionRecord> actionRecords() {
        return List.copyOf(actions);
    }
}
