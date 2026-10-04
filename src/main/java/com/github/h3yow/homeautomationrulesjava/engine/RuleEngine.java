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
 * - 边沿触发 + 每规则一把锁：“评估条件 -> 判定 false→true -> 冷却检查 -> 领取 fireNo -> 执行动作”串行化，
 *   并发事件不可能让同一规则的同一次边沿触发执行两次；
 * - 冷却（最短触发间隔）：实际触发后进入冷却窗口，窗口内出现的 false→true 边沿记为
 *   COOLDOWN_ACTIVE 挡下（不补发、不延后），窗口结束后需重新出现一次 false→true 边沿才再次触发；
 *   未配置冷却的规则行为与纯边沿触发完全一致；
 * - 动作序列按序执行；任一动作失败/条件失效即停止后续动作，并补偿本序列已成功动作；
 * - 整体结果逐动作可解释，成功/失败原因/补偿状态/冷却挡下都进入执行日志。
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleStore ruleStore;
    private final ConditionEvaluator evaluator;
    private final ActionExecutor actionExecutor;
    private final ExecutionLog executionLog;
    private final EngineClock clock;

    private final Map<String, Object> ruleLocks = new ConcurrentHashMap<>();

    public RuleEngine(RuleStore ruleStore, ConditionEvaluator evaluator,
                      ActionExecutor actionExecutor, ExecutionLog executionLog, EngineClock clock) {
        this.ruleStore = ruleStore;
        this.evaluator = evaluator;
        this.actionExecutor = actionExecutor;
        this.executionLog = executionLog;
        this.clock = clock;
    }

    /**
     * 针对一次已确认的状态推进，评估所有规则。
     * 返回边沿相关的结果：实际触发、条件持续满足（STILL_SATISFIED）、
     * 边沿被冷却挡下（COOLDOWN_ACTIVE）都会返回并落入轨迹；
     * 条件不满足（“条件没到”）不产生任何记录。
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
            // 计时口径：冷却判定只取“进入规则串行评估这一刻”的引擎时钟值一次，
            // 整个判定段内复用同一 now，不二次采样墙钟，不用事件自带 eventTime。
            long now = clock.now();

            ConditionEvaluator.Evaluation evaluation = evaluator.evaluate(rule.conditions());
            boolean edge = ruleStore.transitionToSatisfied(rule.id(), evaluation.allMatched());

            if (!evaluation.allMatched()) {
                // 条件不成立：重置边沿，不触发，也不产生记录（“条件没到”）。
                return null;
            }
            if (!edge) {
                // 条件持续满足：边沿状态仍为 true，非新的 false→true。
                // 产生一条可追溯的 skip 记录，与“被冷却挡下”明确区分。
                log.info("规则 {} 条件持续满足（非边沿），不重复触发。判定: {}",
                        rule.id(), evaluation.describe());
                return RuleFireOutcome.skipped(rule.id(), rule.version(),
                        RuleSkipReason.STILL_SATISFIED, rule.cooldownMillis(), null,
                        "rule " + rule.id() + " 未触发: 条件持续满足（STILL_SATISFIED），"
                                + "非新的 false→true 边沿，不重复触发。判定: " + evaluation.describe());
            }

            // false→true 边沿发生。若配置了冷却，检查此刻是否仍处于冷却窗口内。
            Long cooldown = rule.cooldownMillis();
            if (cooldown != null) {
                long lastFired = ruleStore.lastFiredAt(rule.id());
                if (lastFired > 0L) {
                    long elapsed = now - lastFired;
                    if (elapsed < cooldown) {
                        long remaining = cooldown - elapsed;
                        log.info("规则 {} false→true 边沿被冷却挡下: 距上次触发 {}ms < cooldown {}ms，"
                                        + "剩余 {}ms；冷却结束后需再次出现 false→true 边沿才触发。判定: {}",
                                rule.id(), elapsed, cooldown, remaining, evaluation.describe());
                        return RuleFireOutcome.skipped(rule.id(), rule.version(),
                                RuleSkipReason.COOLDOWN_ACTIVE, cooldown, remaining,
                                "rule " + rule.id() + " 未触发: false→true 边沿发生在冷却期内"
                                        + "（COOLDOWN_ACTIVE），距上次触发 " + elapsed + "ms < 最短触发间隔 "
                                        + cooldown + "ms，剩余约 " + remaining + "ms；本次触发被挡下，"
                                        + "冷却结束后需再次出现 false→true 边沿才会触发。判定: "
                                        + evaluation.describe());
                    }
                }
            }

            long fireNo = ruleStore.nextFireNo(rule.id());
            // 冷却窗口起点 = 本次实际触发的判定时刻（领取 fireNo 的同一临界区内），
            // 与动作成功/失败无关，保证“最短触发间隔”口径确定。
            if (cooldown != null) {
                ruleStore.markFiredAt(rule.id(), now);
            }
            log.info("规则 {} 边沿触发 fireNo={} cooldown={} 判定: {}",
                    rule.id(), fireNo, cooldown, evaluation.describe());

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
                String summary = buildPartialSummary(rule, fireNo, records, compensated,
                        succeeded.size(), rec);
                return RuleFireOutcome.fired(
                        rule.id(), rule.version(), fireNo, List.copyOf(records), summary);
            }

            String summary;
            if (records.isEmpty()) {
                summary = "rule " + rule.id() + " fired (fireNo=" + fireNo + ") but has no actions";
            } else {
                summary = "rule " + rule.id() + " fired (fireNo=" + fireNo
                        + "): all " + records.size() + " action(s) succeeded";
            }
            return RuleFireOutcome.fired(
                    rule.id(), rule.version(), fireNo, List.copyOf(records), summary);
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
                // 同步回填执行轨迹：设备状态、动作记录、最终结论三者必须一致。
                executionLog.replaceAction(marked);
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
                                       int succeededCount,
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
        // 只有全部已成功动作都补偿完成，才允许下“已回滚到触发前状态”的结论；
        // 任一补偿失败都必须明确提示人工介入，且此时设备状态保持动作后的值，两边一致。
        parts.add("compensated " + compensated.size() + "/" + succeededCount
                + " previously succeeded action(s); "
                + (compensated.size() < succeededCount
                ? "SOME COMPENSATION FAILED, device state NOT restored, manual intervention needed"
                : "system rolled back to pre-fire state"));
        return String.join("\n", parts);
    }
}
