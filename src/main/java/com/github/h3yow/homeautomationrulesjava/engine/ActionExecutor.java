package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.device.DeviceCommandException;
import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionExecutionRecord;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作执行器：负责“一个动作”的幂等执行、重试、条件复核与补偿。
 *
 * 幂等语义：
 * - 每个 idempotencyKey 全局只执行一次成功副作用。相同键的并发/重复调用，
 *   只有第一个真正执行，其余直接复用已产出的记录（在本地用结果缓存兜底，
 *   DeviceGateway 对物理副作用另有一道幂等保障）。
 * - 重试最多 maxAttempts 次；只有 DEVICE_UNREACHABLE / EXECUTION_TIMEOUT 可重试，
 *   CONDITION_RECHECK_FAILED 属语义失效，立即终止不重试。
 * - 执行前复核条件：若规则条件在动作下发瞬间已不成立，跳过该动作并标记原因，
 *   防止对过期结论下发命令。
 * - 动作失败后执行补偿（把此前本序列已成功的动作回滚为原值），补偿本身也幂等。
 */
@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final DeviceGateway gateway;
    private final DeviceRegistry deviceRegistry;
    private final StateStore stateStore;
    private final ConditionEvaluator evaluator;
    private final ExecutionLog executionLog;

    private final int maxAttempts;
    private final long retryBackoffMs;

    /** idempotencyKey -> 已完成的执行记录（并发/重复调用复用，绝不二次执行）。 */
    private final Map<String, ActionExecutionRecord> finishedRecords = new ConcurrentHashMap<>();
    /** 正在执行中的键，用于并发调用时等待首个执行完成。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** 已完成补偿的键，保证补偿幂等不重复。 */
    private final Set<String> compensatedKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public ActionExecutor(DeviceGateway gateway, DeviceRegistry deviceRegistry, StateStore stateStore,
                          ConditionEvaluator evaluator, ExecutionLog executionLog) {
        this(gateway, deviceRegistry, stateStore, evaluator, executionLog, 3, 5L);
    }

    public ActionExecutor(DeviceGateway gateway, DeviceRegistry deviceRegistry, StateStore stateStore,
                          ConditionEvaluator evaluator, ExecutionLog executionLog,
                          int maxAttempts, long retryBackoffMs) {
        this.gateway = gateway;
        this.deviceRegistry = deviceRegistry;
        this.stateStore = stateStore;
        this.evaluator = evaluator;
        this.executionLog = executionLog;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * 幂等执行一个动作。
     *
     * @param idempotencyKey  ruleId:fireNo:actionDefId
     * @param recheckConditions 下发前需要复核仍成立的条件组（为空表示不复核）
     * @param preActionSnapshots 触发时刻的状态快照，用于与当前状态比较判定条件是否失效
     */
    public ActionExecutionRecord execute(String idempotencyKey, Action action,
                                         List<Condition> recheckConditions,
                                         Map<String, com.github.h3yow.homeautomationrulesjava.model.DeviceState>
                                                 preActionSnapshots) {
        ActionExecutionRecord cached = finishedRecords.get(idempotencyKey);
        if (cached != null) {
            log.info("动作幂等命中，直接复用结果 key={} status={}", idempotencyKey, cached.status());
            return cached;
        }
        Object monitor = locks.computeIfAbsent(idempotencyKey, k -> new Object());
        synchronized (monitor) {
            cached = finishedRecords.get(idempotencyKey);
            if (cached != null) {
                return cached;
            }
            ActionExecutionRecord rec = doExecute(idempotencyKey, action, recheckConditions, preActionSnapshots);
            finishedRecords.put(idempotencyKey, rec);
            executionLog.appendAction(rec);
            return rec;
        }
    }

    private ActionExecutionRecord doExecute(String key, Action action,
                                            List<Condition> recheckConditions,
                                            Map<String, com.github.h3yow.homeautomationrulesjava.model.DeviceState>
                                                    preActionSnapshots) {
        // 1) 动作值合法性校验（非法动作值不应进入下发）。
        try {
            validateActionValue(action);
        } catch (IllegalArgumentException ex) {
            return finish(key, action, ActionStatus.FAILED, 0,
                    ActionFailureReason.INVALID_ACTION, false,
                    List.of("invalid action value: " + ex.getMessage()));
        }

        // 2) 执行前条件复核：条件已失效则跳过，不下发。
        if (!recheckConditions.isEmpty() && !evaluator.evaluate(recheckConditions).allMatched()) {
            String detail = "条件复核失败，动作跳过: " + evaluator.evaluate(recheckConditions).describe();
            log.info("key={} {}", key, detail);
            return finish(key, action, ActionStatus.SKIPPED, 0,
                    ActionFailureReason.CONDITION_RECHECK_FAILED, false, List.of(detail));
        }

        // 3) 带重试下发（幂等键透传给网关）。
        List<String> errors = new ArrayList<>();
        int attempts = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            attempts = i;
            try {
                gateway.sendCommand(key, action.targetDeviceId(), action.property(), action.value());
                log.info("动作下发成功 key={} attempt={} -> {}={}",
                        key, i, action.targetDeviceId(), action.property(), action.value());
                // 物理副作用成功，回写为已确认状态（用单调时间戳派生 seq 空间与上报独立）。
                stateStore.applyCommand(action.targetDeviceId(), action.property(), action.value(),
                        System.currentTimeMillis());
                return finish(key, action, ActionStatus.SUCCESS, attempts, null, false, List.of());
            } catch (DeviceCommandException ex) {
                errors.add("attempt " + i + " -> " + ex.reason() + ": " + ex.getMessage());
                log.warn("动作下发失败 key={} attempt={} reason={}", key, i, ex.reason());
                if (i < maxAttempts) {
                    sleepBackoff(i);
                }
            }
        }
        ActionFailureReason finalReason = dominantReason(errors);
        return finish(key, action, ActionStatus.FAILED, attempts, finalReason, false, errors);
    }

    /**
     * 补偿：把动作目标属性恢复为触发前快照中的原值。幂等。
     * 用于部分失败时回滚本触发实例内已成功的动作。
     */
    public boolean compensate(String fireKey, Action action,
                              Map<String, com.github.h3yow.homeautomationrulesjava.model.DeviceState> preSnapshots) {
        String compKey = "comp:" + fireKey + ":" + action.actionDefId();
        if (!compensatedKeys.add(compKey)) {
            log.info("补偿幂等命中，跳过 key={}", compKey);
            return true;
        }
        var pre = preSnapshots.get(action.targetDeviceId());
        Object original = pre == null ? null : pre.valueOf(action.property());
        if (original == null) {
            log.info("无触发前原值可回滚 key={} {}={}", compKey, action.targetDeviceId(), action.property());
            return true;
        }
        try {
            gateway.sendCommand(compKey, action.targetDeviceId(), action.property(), original);
            log.info("补偿成功 key={} 回滚 {}={}", compKey, action.targetDeviceId(), action.property(), original);
            return true;
        } catch (DeviceCommandException ex) {
            log.error("补偿失败 key={} reason={}", compKey, ex.reason());
            return false;
        }
    }

    /** 供记录补偿结果：把某动作记录标记为已补偿。 */
    public ActionExecutionRecord markCompensated(ActionExecutionRecord rec) {
        return new ActionExecutionRecord(rec.idempotencyKey(), rec.action(), rec.status(), rec.attempts(),
                rec.failureReason(), true, rec.errors());
    }

    private void validateActionValue(Action action) {
        Device device = deviceRegistry.find(action.targetDeviceId());
        if (device == null) {
            throw new IllegalArgumentException("target device not registered: " + action.targetDeviceId());
        }
        PropertySpec spec = device.specOf(action.property());
        if (spec == null) {
            throw new IllegalArgumentException("device " + action.targetDeviceId()
                    + " has no property " + action.property());
        }
        spec.normalize(action.value());
    }

    private ActionFailureReason dominantReason(List<String> errors) {
        // 所有失败原因中，取最后一次网关异常的可区分原因；解析失败信息中的原因名。
        ActionFailureReason reason = ActionFailureReason.DEVICE_UNREACHABLE;
        for (ActionFailureReason r : ActionFailureReason.values()) {
            if (errors.get(errors.size() - 1).contains(r.name())) {
                reason = r;
                break;
            }
        }
        return reason;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(retryBackoffMs * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private ActionExecutionRecord finish(String key, Action action, ActionStatus status, int attempts,
                                         ActionFailureReason reason, boolean compensated, List<String> errors) {
        return new ActionExecutionRecord(key, action, status, attempts, reason, compensated,
                List.copyOf(errors));
    }

    /** 测试/演示重置：清空幂等结果缓存、执行锁与补偿记录。 */
    public void reset() {
        finishedRecords.clear();
        locks.clear();
        compensatedKeys.clear();
    }
}
