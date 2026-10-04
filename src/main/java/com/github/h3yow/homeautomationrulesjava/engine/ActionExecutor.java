package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.device.DeviceCommandException;
import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionExecutionRecord;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.CompensationStatus;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
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
 *
 * 补偿（回滚）语义：
 * - 动作序列中途失败后，把本序列此前已成功动作涉及的属性恢复为“触发前那一刻的原值”，
 *   包括触发前该设备本来就没有该属性值的情况（恢复后查询侧该属性不存在）；
 * - 同一 (设备,属性) 在本序列里被多个动作先后改过，也只回滚一次、直接恢复为触发前原值；
 * - 回滚通过 StateStore 的 CAS（reportEpoch）完成：触发后若已有新的上报被确认，回滚跳过，
 *   绝不把已确认的新状态盖回旧值（返回 SUPERSEDED_BY_NEW_REPORT）；
 * - 回滚成功时物理侧与查询侧状态同步恢复；回滚失败或拿不到触发前原值时返回明确状态，
 *   绝不表现为“已恢复”。补偿本身幂等。
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
    /** 已完成补偿的 (fireKey,设备,属性) -> 补偿结果，保证补偿幂等、同一属性只回滚一次。 */
    private final Map<String, CompensationStatus> compensated = new ConcurrentHashMap<>();
    /** 补偿去重键对应的执行锁。 */
    private final Map<String, Object> compensateLocks = new ConcurrentHashMap<>();

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
                                         Map<String, DeviceState> preActionSnapshots) {
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
                                            Map<String, DeviceState> preActionSnapshots) {
        // 1) 动作值合法性校验（非法动作值不应进入下发）。
        try {
            validateActionValue(action);
        } catch (IllegalArgumentException ex) {
            return finish(key, action, ActionStatus.FAILED, 0,
                    ActionFailureReason.INVALID_ACTION, List.of("invalid action value: " + ex.getMessage()));
        }

        // 2) 执行前条件复核：条件已失效则跳过，不下发。
        if (!recheckConditions.isEmpty() && !evaluator.evaluate(recheckConditions).allMatched()) {
            String detail = "条件复核失败，动作跳过: " + evaluator.evaluate(recheckConditions).describe();
            log.info("key={} {}", key, detail);
            return finish(key, action, ActionStatus.SKIPPED, 0,
                    ActionFailureReason.CONDITION_RECHECK_FAILED, List.of(detail));
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
                // 物理副作用成功，回写为已确认状态（不占用上报序号、不推进 reportEpoch）。
                stateStore.applyCommand(action.targetDeviceId(), action.property(), action.value(),
                        System.currentTimeMillis());
                return finish(key, action, ActionStatus.SUCCESS, attempts, null, List.of());
            } catch (DeviceCommandException ex) {
                errors.add("attempt " + i + " -> " + ex.reason() + ": " + ex.getMessage());
                log.warn("动作下发失败 key={} attempt={} reason={}", key, i, ex.reason());
                if (i < maxAttempts) {
                    sleepBackoff(i);
                }
            }
        }
        ActionFailureReason finalReason = dominantReason(errors);
        return finish(key, action, ActionStatus.FAILED, attempts, finalReason, errors);
    }

    /**
     * 补偿：把动作目标属性恢复为触发前快照中的原值。幂等；同一 (fireKey,设备,属性) 只恢复一次。
     * 用于部分失败时回滚本触发实例内已成功的动作（同设备多动作先后改动时按属性去重）。
     *
     * @return 补偿的最终状态，绝不允许在未恢复时返回 RESTORED
     */
    public CompensationStatus compensate(String fireKey, Action action, Map<String, DeviceState> preSnapshots) {
        String deviceId = action.targetDeviceId();
        String property = action.property();
        String dedupeKey = fireKey + ":" + deviceId + ":" + property;
        CompensationStatus existing = compensated.get(dedupeKey);
        if (existing != null) {
            log.info("补偿幂等命中，复用结果 key={} status={}", dedupeKey, existing);
            return existing;
        }
        Object monitor = compensateLocks.computeIfAbsent(dedupeKey, k -> new Object());
        synchronized (monitor) {
            existing = compensated.get(dedupeKey);
            if (existing != null) {
                return existing;
            }
            CompensationStatus status = doCompensate(fireKey, action, preSnapshots, dedupeKey);
            compensated.put(dedupeKey, status);
            return status;
        }
    }

    private CompensationStatus doCompensate(String fireKey, Action action,
                                            Map<String, DeviceState> preSnapshots, String dedupeKey) {
        String deviceId = action.targetDeviceId();
        String property = action.property();
        String compKey = "comp:" + fireKey + ":" + property;

        DeviceState pre = preSnapshots.get(deviceId);
        // 触发前目标设备没有任何已确认快照：拿不到触发前原值，不能声称已恢复。
        if (pre == null) {
            log.error("无法补偿 key={}：触发前设备 {} 无快照，拿不到原值，需人工介入", compKey, deviceId);
            return CompensationStatus.ORIGINAL_UNAVAILABLE;
        }
        DeviceState.ConfirmedProperty original = pre.get(property);
        boolean originalPresent = original != null;
        Object originalValue = originalPresent ? original.value() : null;
        long expectedEpoch = originalPresent ? original.reportEpoch() : 0L;

        StateStore.RestoreResult result = stateStore.restoreIfNoNewReport(
                deviceId, property, expectedEpoch, originalPresent, originalValue,
                System.currentTimeMillis(),
                () -> {
                    // 物理侧恢复与查询侧 CAS 在同一设备临界区内完成，避免并发上报时互相覆盖。
                    if (originalPresent) {
                        gateway.sendCommand(compKey, deviceId, property, originalValue);
                    } else {
                        gateway.clearPhysical(compKey, deviceId, property);
                    }
                });

        switch (result.status()) {
            case RESTORED -> {
                log.info("补偿成功 key={} {}={} 恢复为触发前原值(present={})",
                        compKey, deviceId, property, originalPresent ? originalValue : "<absent>", originalPresent);
                return CompensationStatus.RESTORED;
            }
            case SUPERSEDED_BY_NEW_REPORT -> {
                log.info("补偿跳过 key={} {}={}：触发后已有新的上报被确认，保留新状态不回滚",
                        compKey, deviceId, property);
                return CompensationStatus.SUPERSEDED_BY_NEW_REPORT;
            }
            case FAILED -> {
                log.error("补偿失败 key={} {}={}：物理回滚命令失败，需人工介入", compKey, deviceId, property);
                return CompensationStatus.FAILED;
            }
            default -> throw new IllegalStateException("unknown restore status: " + result.status());
        }
    }

    /** 供记录补偿结果：把某动作记录的补偿状态更新为最终值。 */
    public ActionExecutionRecord markCompensation(ActionExecutionRecord rec, CompensationStatus status) {
        return new ActionExecutionRecord(rec.idempotencyKey(), rec.action(), rec.status(), rec.attempts(),
                rec.failureReason(), status, rec.errors());
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
                                         ActionFailureReason reason, List<String> errors) {
        return new ActionExecutionRecord(key, action, status, attempts, reason,
                CompensationStatus.NOT_NEEDED, List.copyOf(errors));
    }

    /** 测试/演示重置：清空幂等结果缓存、执行锁与补偿记录。 */
    public void reset() {
        finishedRecords.clear();
        locks.clear();
        compensated.clear();
        compensateLocks.clear();
    }
}
