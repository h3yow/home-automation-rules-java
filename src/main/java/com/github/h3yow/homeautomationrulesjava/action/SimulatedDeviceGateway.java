package com.github.h3yow.homeautomationrulesjava.action;

import com.github.h3yow.homeautomationrulesjava.device.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟设备网关。
 *
 * 幂等语义（两个集合）：
 *  - seenKeys：所有受理过的幂等键；
 *  - effectiveKeys：最终产生了副作用（执行成功）的幂等键。
 * 同一 idempotencyKey：
 *  - 已成功 -> 直接成功返回，绝不产生第二次副作用（重复下发/补偿/重放安全）；
 *  - 曾失败 -> 允许真正重试（重试可能成功），执行器对同一动作的所有尝试复用同一键；
 *  - 未见过 -> 首次执行。
 *
 * 另支持故障注入：设备离线（DEVICE_UNREACHABLE）、前 N 次失败、慢调用（TIMEOUT）。
 */
@SuppressWarnings("unused")
@Component
public class SimulatedDeviceGateway implements DeviceGateway {

    private final DeviceRegistry deviceRegistry;
    private final Map<String, FailurePlan> failPlans = new ConcurrentHashMap<>();
    private final Set<String> offline = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> delayMillis = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenKeys = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> effectiveKeys = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** 每设备动作执行前的回调（确定性测试钩子，例如在动作期间翻转状态）。 */
    private final Map<String, Runnable> beforeExecuteHooks = new ConcurrentHashMap<>();

    public SimulatedDeviceGateway(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public void execute(ActionCommand command) {
        DeviceSpec spec = deviceRegistry.find(command.deviceId())
                .orElseThrow(() -> new ActionException(FailureKind.UNREGISTERED_TARGET,
                        "target device not registered: " + command.deviceId()));
        if (Double.isNaN(command.value())
                || command.value() < spec.minValue() || command.value() > spec.maxValue()) {
            throw new ActionException(FailureKind.INVALID_TARGET_VALUE,
                    "target value out of range [" + spec.minValue() + ", "
                            + spec.maxValue() + "]: " + command.value());
        }

        // 第一阶段（锁内）：幂等判定与故障名额消费，得出"该做什么"
        Decision decision;
        synchronized (lockFor(command.deviceId())) {
            Set<String> seen = seenKeys.computeIfAbsent(command.deviceId(), k -> new HashSet<>());
            Set<String> effective = effectiveKeys.computeIfAbsent(
                    command.deviceId(), k -> new HashSet<>());

            if (seen.add(command.idempotencyKey())) {
                // 首次见到该键
            } else if (effective.contains(command.idempotencyKey())) {
                // 之前已成功：幂等短路，无重复副作用
                return;
            }
            // else：之前见过但未成功（失败重试），继续真正执行

            if (offline.contains(command.deviceId())) {
                throw new ActionException(FailureKind.DEVICE_UNREACHABLE,
                        "device offline: " + command.deviceId());
            }
            FailurePlan plan = failPlans.get(command.deviceId());
            if (plan != null && plan.remainingFailures() > 0) {
                FailurePlan next = plan.consume();
                if (next.remainingFailures() <= 0) {
                    failPlans.remove(command.deviceId());
                } else {
                    failPlans.put(command.deviceId(), next);
                }
                throw new ActionException(plan.kind(),
                        "injected " + plan.kind() + " for " + command.deviceId()
                                + ", remaining=" + next.remainingFailures());
            }
            decision = new Decision(command.idempotencyKey(),
                    delayMillis.getOrDefault(command.deviceId(), 0L),
                    beforeExecuteHooks.get(command.deviceId()));
        }

        // 第二阶段（锁外）：测试钩子 + 模拟执行；此处允许嵌套调用引擎而不死锁
        if (decision.hook() != null) {
            decision.hook().run();
        }
        if (decision.delayMillis() > 0) {
            try {
                Thread.sleep(decision.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActionException(FailureKind.TIMEOUT,
                        "interrupted while executing: " + command.deviceId());
            }
        }

        // 第三阶段（锁内）：副作用落账。重试期间若同键已被其它路径成功，则不重复记账
        synchronized (lockFor(command.deviceId())) {
            Set<String> effective = effectiveKeys.computeIfAbsent(
                    command.deviceId(), k -> new HashSet<>());
            effective.add(decision.idempotencyKey());
        }
    }

    /** 锁内判定的结果。 */
    private record Decision(String idempotencyKey, long delayMillis, Runnable hook) {
    }

    private Object lockFor(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new Object());
    }

    // ---- 测试注入接口 ----

    /** 接下来 times 次对该设备的调用按 kind 失败；times 次后恢复（用于验证有界重试）。 */
    public void failNext(String deviceId, FailureKind kind, int times) {
        failPlans.put(deviceId, new FailurePlan(kind, times));
    }

    /** 设置设备离线/恢复。 */
    public void setOffline(String deviceId, boolean isOffline) {
        if (isOffline) {
            offline.add(deviceId);
        } else {
            offline.remove(deviceId);
        }
    }

    /** 设置每次执行延迟（模拟慢设备；由执行器的超时判定转为 TIMEOUT）。 */
    public void setDelay(String deviceId, long millis) {
        delayMillis.put(deviceId, millis);
    }

    /** 设置设备动作执行前的确定性回调（测试钩子）。 */
    public void setBeforeExecuteHook(String deviceId, Runnable hook) {
        if (hook == null) {
            beforeExecuteHooks.remove(deviceId);
        } else {
            beforeExecuteHooks.put(deviceId, hook);
        }
    }

    /** 该设备实际产生副作用（成功）的命令数（去重后）。 */
    public int executionCount(String deviceId) {
        Set<String> keys = effectiveKeys.get(deviceId);
        return keys == null ? 0 : keys.size();
    }

    /** 全部设备实际产生副作用的总次数。 */
    public int totalExecutionCount() {
        return effectiveKeys.values().stream().mapToInt(Set::size).sum();
    }

    /** 已生效幂等键集合（不可变快照）。 */
    public Set<String> executedKeys(String deviceId) {
        Set<String> keys = effectiveKeys.get(deviceId);
        return keys == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(keys));
    }

    /** 测试辅助：清空故障注入、延迟与计数。 */
    public void reset() {
        failPlans.clear();
        offline.clear();
        delayMillis.clear();
        seenKeys.values().forEach(Set::clear);
        effectiveKeys.values().forEach(Set::clear);
        seenKeys.clear();
        effectiveKeys.clear();
    }

    private record FailurePlan(FailureKind kind, int remainingFailures) {
        FailurePlan consume() {
            return new FailurePlan(kind, remainingFailures - 1);
        }
    }
}
