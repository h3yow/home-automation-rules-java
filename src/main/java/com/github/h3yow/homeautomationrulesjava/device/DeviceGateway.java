package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地设备网关（模拟真实设备通信，无任何外部服务）。
 *
 * 职责与语义：
 * - sendCommand 模拟向设备下发一次命令，只有它会真正改变“设备物理侧状态”；
 * - 每个 idempotencyKey 只允许产生一次物理副作用：重试同一个键直接返回首次结果，
 *   因此“重试不产生重复副作用”在网关侧也有一道硬保障；
 * - 通过 fail* 方法注入故障，区分 DEVICE_UNREACHABLE 与 EXECUTION_TIMEOUT；
 * - 瞬时故障支持“前 failTimes 次失败、之后成功”，用于模拟重试后恢复。
 */
@Component
public class DeviceGateway {

    /** 某个 (device,property) 的注入故障配置。 */
    private static final class Fault {
        private final ActionFailureReason reason;
        private final int failTimes; // 剩余失败次数；Integer.MAX_VALUE 表示永久
        private int remaining;

        private Fault(ActionFailureReason reason, int failTimes) {
            this.reason = reason;
            this.failTimes = failTimes;
            this.remaining = failTimes;
        }
    }

    private final Map<String, Object> physicalState = new ConcurrentHashMap<>();
    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
    /** 幂等键 -> 是否已成功执行过物理副作用。 */
    private final Set<String> executedKeys = ConcurrentHashMap.newKeySet();
    /** 真实成功改变物理侧的命令次数（不含失败尝试与幂等短路），供断言下发次数。 */
    private final java.util.concurrent.atomic.AtomicInteger successfulCommandCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private static String faultKey(String deviceId, String property) {
        return deviceId + "#" + property;
    }

    private static String physicalKey(String deviceId, String property) {
        return deviceId + "#" + property;
    }

    /** 注入永久故障（设备不可达或执行超时）。 */
    public void failPermanently(String deviceId, String property, ActionFailureReason reason) {
        faults.put(faultKey(deviceId, property), new Fault(reason, Integer.MAX_VALUE));
    }

    /** 注入瞬时故障：接下来 times 次命令失败，之后恢复成功。 */
    public void failTransiently(String deviceId, String property, ActionFailureReason reason, int times) {
        faults.put(faultKey(deviceId, property), new Fault(reason, times));
    }

    /** 解除注入故障（模拟设备恢复）。 */
    public void recover(String deviceId, String property) {
        faults.remove(faultKey(deviceId, property));
    }

    /** 测试辅助：读取设备物理侧当前值。 */
    public Object physicalValue(String deviceId, String property) {
        return physicalState.get(physicalKey(deviceId, property));
    }

    /** 测试辅助：某幂等键是否已经执行过物理副作用。 */
    public boolean hasExecuted(String idempotencyKey) {
        return executedKeys.contains(idempotencyKey);
    }

    /**
     * 下发命令。
     *
     * @param idempotencyKey 全局副作用幂等键；相同键的重复/重试调用不会二次改变物理状态
     * @throws DeviceCommandReason 区分不可达/超时
     */
    public void sendCommand(String idempotencyKey, String deviceId, String property, Object value) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // 幂等短路：同一命令已经成功执行过，直接返回，绝无第二次副作用。
        if (!executedKeys.add(idempotencyKey)) {
            return;
        }
        Fault fault = faults.get(faultKey(deviceId, property));
        if (fault != null && fault.remaining > 0) {
            if (fault.remaining != Integer.MAX_VALUE) {
                fault.remaining--;
            }
            // 本次尝试失败：该键尚未真正生效，允许后续重试重新尝试。
            executedKeys.remove(idempotencyKey);
            throw new DeviceCommandException(fault.reason,
                    "command to " + deviceId + "." + property + " failed: " + fault.reason);
        }
        // 唯一一次真实副作用。
        physicalState.put(physicalKey(deviceId, property), value);
        successfulCommandCount.incrementAndGet();
    }

    /**
     * 清除设备物理侧属性（模拟“复位/移除”），用于回滚到“触发前该属性本来没有值”的状态。
     * 幂等语义与 {@link #sendCommand} 相同：同一幂等键只产生一次物理副作用。
     *
     * @throws DeviceCommandException 与下发命令相同的可区分故障
     */
    public void clearPhysical(String idempotencyKey, String deviceId, String property) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!executedKeys.add(idempotencyKey)) {
            return;
        }
        Fault fault = faults.get(faultKey(deviceId, property));
        if (fault != null && fault.remaining > 0) {
            if (fault.remaining != Integer.MAX_VALUE) {
                fault.remaining--;
            }
            executedKeys.remove(idempotencyKey);
            throw new DeviceCommandException(fault.reason,
                    "clear command to " + deviceId + "." + property + " failed: " + fault.reason);
        }
        physicalState.remove(physicalKey(deviceId, property));
        successfulCommandCount.incrementAndGet();
    }

    /** 测试辅助：真实成功改变物理侧的命令次数（含回滚命令），不含失败尝试/幂等短路。 */
    public int successfulCommandCount() {
        return successfulCommandCount.get();
    }

    /** 测试辅助：统计设备物理侧被写入的属性数量。 */
    public int executedCommandCount() {
        return physicalState.size();
    }

    /** 清空全部状态与故障（演示/测试重置）。 */
    public void reset() {
        physicalState.clear();
        faults.clear();
        executedKeys.clear();
        successfulCommandCount.set(0);
    }
}
