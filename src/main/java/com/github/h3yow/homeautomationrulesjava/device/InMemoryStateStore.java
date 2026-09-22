package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.model.RejectReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存状态存储。
 *
 * 一致性保证：
 * 1) 同一设备的事件确认在 ConcurrentHashMap.compute 内原子完成，并发上报被串行化，版本单调递增；
 * 2) eventId 全局去重：重复事件返回 DUPLICATE，不推进状态、不触发规则；
 * 3) 乱序/迟到：eventTime 不大于已确认 eventTime 的事件返回 STALE_EVENT，状态绝不回退；
 * 4) 未注册设备 / 非法取值在状态变更前拒绝，原因可区分。
 */
@SuppressWarnings("unused")
@Component
public class InMemoryStateStore implements StateStore {

    private final DeviceRegistry deviceRegistry;
    private final ConcurrentMap<String, DeviceState> states = new ConcurrentHashMap<>();
    /** 已处理事件ID -> 该事件当时确认到的状态版本。 */
    private final ConcurrentMap<String, Long> processedEvents = new ConcurrentHashMap<>();

    public InMemoryStateStore(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public EventReceipt accept(EventReport event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (event.deviceId() == null || event.deviceId().isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }

        // 未注册设备：拒绝（不做任何状态变更）
        Optional<DeviceSpec> spec = deviceRegistry.find(event.deviceId());
        if (spec.isEmpty()) {
            return new EventReceipt(ReceiptStatus.REJECTED,
                    RejectReason.DEVICE_NOT_REGISTERED, null);
        }

        // 非法取值：拒绝
        if (Double.isNaN(event.value())
                || event.value() < spec.get().minValue()
                || event.value() > spec.get().maxValue()) {
            return new EventReceipt(ReceiptStatus.REJECTED, RejectReason.INVALID_VALUE, null);
        }

        /*
         * 按设备原子串行化：去重判定 -> 乱序判定 -> 状态推进，三者不可分割。
         * 对于"重复/乱序"两种不改变映射的分支，通过控制异常把回执带出 compute。
         */
        try {
            DeviceState confirmed = states.compute(event.deviceId(), (id, current) -> {
                if (processedEvents.containsKey(event.eventId())) {
                    long v = current != null ? current.version() : -1L;
                    throw new ComputedReceipt(new EventReceipt(
                            ReceiptStatus.DUPLICATE, null, v < 0 ? null : v));
                }
                if (current != null && event.eventTime() <= current.eventTime()) {
                    throw new ComputedReceipt(new EventReceipt(
                            ReceiptStatus.REJECTED, RejectReason.STALE_EVENT, current.version()));
                }
                long version = current == null ? 1L : current.version() + 1L;
                DeviceState next = new DeviceState(id, event.value(), version,
                        event.eventTime(), System.currentTimeMillis());
                processedEvents.put(event.eventId(), version);
                return next;
            });
            return new EventReceipt(ReceiptStatus.ACCEPTED, null, confirmed.version());
        } catch (ComputedReceipt carrier) {
            return carrier.receipt();
        }
    }

    @Override
    public Optional<DeviceState> current(String deviceId) {
        return Optional.ofNullable(states.get(deviceId));
    }

    @Override
    public List<DeviceState> snapshot() {
        List<DeviceState> list = new ArrayList<>(states.values());
        list.sort(java.util.Comparator.comparing(DeviceState::deviceId)
                .thenComparingLong(DeviceState::version));
        return List.copyOf(list);
    }

    /** 测试辅助：清空全部状态与去重记录。 */
    public void clear() {
        states.clear();
        processedEvents.clear();
    }

    /** 已处理事件数（测试/观测用）。 */
    public int processedEventCount() {
        return processedEvents.size();
    }

    /** 在 ConcurrentHashMap.compute 中携带"重复/乱序"回执的控制异常。 */
    static final class ComputedReceipt extends RuntimeException {
        private final EventReceipt receipt;

        ComputedReceipt(EventReceipt receipt) {
            super(null, null, false, false);
            this.receipt = receipt;
        }

        EventReceipt receipt() {
            return receipt;
        }
    }
}
