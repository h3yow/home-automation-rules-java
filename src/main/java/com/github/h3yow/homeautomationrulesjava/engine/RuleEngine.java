package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionExecutionRecord;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.model.RuleSkipReason;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则引擎：在一条事件被“确认”后评估全部启用规则。
 *
 * 关键保证：
 * - 边沿触发 + 每规则一把锁：“评估条件 -> 判定 false→true -> 冷却检查 -> 领取 fireNo ->
 *   执行动作”串行化，并发事件不可能让同一规则的同一次边沿触发执行两次，
 *   冷却窗口的读取与推进也在同一把锁内原子完成，各规则互不影响；
 * - 冷却（cooldownMillis &gt; 0）：一次成功触发后，处理时刻落在
 *   [lastFiredAt, lastFiredAt + cooldownMillis) 的边沿一律挡下且不刷新计时基准；
 *   冷却结束后出现的“下一次 false→true”正常触发。冷却为 0 时行为与旧版本完全一致；
 * - 动作序列按序执行；任一动作失败/条件失效即停止后续动作，并补偿本序列已成功动作；
 * - 整体结果逐动作可解释；“持续满足不重复触发”和“被冷却挡下”是两种可区分结论，
 *   成功/失败原因/补偿状态都进入执行日志。
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleStore ruleStore;
    private final ConditionEvaluator evaluator;
    private final ActionExecutor actionExecutor;
    private final ExecutionLog executionLog;
    private final RuleClock clock;

    private final Map<String, Object> ruleLocks = new ConcurrentHashMap<>();

    public RuleEngine(RuleStore ruleStore, ConditionEvaluator evaluator,
                      ActionExecutor actionExecutor, ExecutionLog executionLog, RuleClock clock) {
        this.ruleStore = ruleStore;
        this.evaluator = evaluator;
        this.actionExecutor = actionExecutor;
        this.executionLog = executionLog;
        this.clock = clock;
    }

    /**
     * 针对一次已确认的状态推进，评估所有规则。
     * 仅返回真正触发或边沿相关的结果；未触发规则不产生动作、不产生记录。
     */
    public List<RuleFireOutcome> onStateConfirmed() {
        List<RuleFireOutcome> outcomes = new ArrayList<>();
        for (Rule rule : ruleStore.all()) {
            if (!rule.enabled()) {
                continue;
            }
            RuleFireOutcome outcome = evaluateOne(rule);
            if (outcome != null) {
                outcomes.add(outcome);
                executionLog.appendFire(rule.id(), outcome);
            }
        }
        return outcomes;
    }

    private RuleFireOutcome evaluateOne(Rule rule) {
        Object lock = ruleLocks.computeIfAbsent(rule.id(), k -> new Object());
        synchronized (lock) {
            ConditionEvaluator.Evaluation evaluation = evaluator.evaluate(rule.conditions());
            boolean edge = ruleStore.transitionToSatisfied(rule.id(), evaluation.allMatched());

            if (!evaluation.allMatched()) {
                // 条件不成立：重置边沿，不触发（与旧版本一致，不产生任何记录）。
                return null;
            }
            if (!edge) {
                // 条件持续满足：不重复触发。这是边沿语义下的“正常不触发”，
                // 与“边沿被冷却挡下”明确区分，便于追溯。
                log.info("规则 {} 条件持续满足（非边沿），不重复触发。判定: {}",
                        rule.id(), evaluation.describe());
                String summary = "rule " + rule.id() + " not fired: STILL_SATISFIED (条件持续满足，非 false→true 边沿)";
                return RuleFireOutcome.skipped(rule.id(), rule.version(),
                        RuleSkipReason.STILL_SATISFIED, rule.cooldownMillis(), null, summary);
            }

            // 边沿成立：冷却检查。计时口径为引擎处理时刻（RuleClock），
            // 窗口为左闭右开 [lastFiredAt, lastFiredAt + cooldownMillis)。
            long now = clock.nowMillis();
            long lastFiredAt = ruleStore.lastFiredAt(rule.id());
            if (rule.cooldownMillis() > 0 && lastFiredAt > 0 && now - lastFiredAt < rule.cooldownMillis()) {
                long retryAt = lastFiredAt + rule.cooldownMillis();
                long elapsed = now - lastFiredAt;
                log.info("规则 {} 边沿出现但处于冷却期: now={} 距上次触发={}ms < cooldown={}ms，挡下本次触发，"
                                + "冷却计时基准不刷新，retryAt={}",
                        rule.id(), now, elapsed, rule.cooldownMillis(), retryAt);
                String summary = "rule " + rule.id() + " not fired: COOLDOWN_ACTIVE"
                        + " (false→true 边沿出现，但距上次成功触发仅 " + elapsed + "ms < 冷却 "
                        + rule.cooldownMillis() + "ms，本次被冷却挡下；冷却不会压过冷却结束后的下一次边沿，"
                        + "下一次可触发边沿的最早处理时刻 retryAt=" + retryAt + ")";
                return RuleFireOutcome.skipped(rule.id(), rule.version(),
                        RuleSkipReason.COOLDOWN_ACTIVE, rule.cooldownMillis(), retryAt, summary);
            }

            long fireNo = ruleStore.nextFireNo(rule.id());
            // 只有真正触发才推进冷却计时基准：被挡下的边沿不刷新，窗口不会被抖动无限延长。
            ruleStore.markFiredAt(rule.id(), now);
            log.info("规则 {} 边沿触发 fireNo={} now={} cooldown={}ms 判定: {}",
                    rule.id(), fireNo, now, rule.cooldownMillis(), evaluation.describe());

            // 触发瞬间状态快照：用于动作前复核与失败补偿的“原值”。
            // 必须同时覆盖“条件设备”（复核用）与“动作目标设备”（补偿回滚取原值用）。
            Map<String, DeviceState> preSnapshots = new java.util.HashMap<>(
                    evaluator.snapshotsFor(rule.conditions()));
            for (Action a : rule.actions()) {
                preSnapshots.computeIfAbsent(a.targetDeviceId(), evaluator::snapshotOf);
            }

            List<ActionExecutionRecord> records = new ArrayList<>();
            List<ActionExecutionRecord> succeeded = new ArrayList<>();
            boolean anyFailure = false;

            for (Action action : rule.actions()) {
                String key = rule.id() + ":" + fireNo + ":" + action.actionDefId();
                ActionExecutionRecord rec = actionExecutor.execute(
                        key, action, rule.conditions(), preSnapshots);
                records.add(rec);

                if (rec.succeeded()) {
                    succeeded.add(rec);
                    continue;
                }

                anyFailure = true;
                // 条件失效：不再继续；设备失败（重试耗尽）：也不再继续。
                log.warn("规则 {} fireNo={} 动作 {} 终止序列: status={} reason={}",
                        rule.id(), fireNo, action.actionDefId(), rec.status(), rec.failureReason());

                // 补偿此前已成功动作，保证部分失败时系统收敛且无重复副作用。
                List<ActionExecutionRecord> compensated = compensateSucceeded(
                        rule.id(), fireNo, succeeded, preSnapshots, records);
                String summary = buildPartialSummary(rule, fireNo, records, compensated, rec);
                RuleFireOutcome failed = RuleFireOutcome.fired(
                        rule.id(), rule.version(), fireNo, rule.cooldownMillis(),
                        List.copyOf(records), summary);
                return failed;
            }

            String summary;
            if (records.isEmpty()) {
                summary = "rule " + rule.id() + " fired (fireNo=" + fireNo + ") but has no actions";
            } else {
                summary = "rule " + rule.id() + " fired (fireNo=" + fireNo
                        + "): all " + records.size() + " action(s) succeeded";
            }
            return RuleFireOutcome.fired(rule.id(), rule.version(), fireNo,
                    rule.cooldownMillis(), List.copyOf(records), summary);
        }
    }

    private List<ActionExecutionRecord> compensateSucceeded(
            String ruleId, long fireNo, List<ActionExecutionRecord> succeeded,
            Map<String, DeviceState> preSnapshots, List<ActionExecutionRecord> allRecords) {
        List<ActionExecutionRecord> compensated = new ArrayList<>();
        String fireKey = ruleId + ":" + fireNo;
        // 逆序补偿更接近“撤销”语义。
        for (int i = succeeded.size() - 1; i >= 0; i--) {
            ActionExecutionRecord rec = succeeded.get(i);
            boolean ok = actionExecutor.compensate(fireKey, rec.action(), preSnapshots);
            if (ok) {
                int idx = allRecords.indexOf(rec);
                ActionExecutionRecord marked = actionExecutor.markCompensated(rec);
                allRecords.set(idx, marked);
                // 同步更新追溯轨迹，保证“最终结论 / 动作记录 / 设备状态”三方一致。
                executionLog.markActionCompensated(rec.idempotencyKey(), marked);
                compensated.add(rec);
            } else {
                log.error("补偿动作失败，需人工介入 key={}", rec.idempotencyKey());
            }
        }
        return compensated;
    }

    private String buildPartialSummary(Rule rule, long fireNo,
                                       List<ActionExecutionRecord> records,
                                       List<ActionExecutionRecord> compensated,
                                       ActionExecutionRecord failedAt) {
        List<String> parts = new ArrayList<>();
        parts.add("rule " + rule.id() + " fired (fireNo=" + fireNo + ") PARTIAL-FAILURE at action '"
                + failedAt.action().actionDefId() + "' reason=" + failedAt.failureReason());
        for (ActionExecutionRecord r : records) {
            parts.add("  action '" + r.action().actionDefId() + "' -> " + r.status()
                    + " (attempts=" + r.attempts() + ")"
                    + (r.failureReason() != null ? " reason=" + r.failureReason() : "")
                    + (r.compensated() ? " [compensated]" : "")
                    + (!r.errors().isEmpty() ? " errors=" + r.errors() : ""));
        }
        parts.add("compensated " + compensated.size() + " previously succeeded action(s); "
                + (compensated.size() < records.size() - 1 ? "SOME COMPENSATION FAILED, manual intervention needed"
                : "system rolled back to pre-fire state"));
        return String.join("\n", parts);
    }
}
