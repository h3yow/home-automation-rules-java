package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备状态存储（本地内存模拟）。
 *
 * 一致性策略：
 * - 每台设备一把锁，同一设备并发上报按“比较并确认”串行化，不同设备互不阻塞；
 * - 每个属性独立记录 lastSeq：只有 seq > lastSeq 才推进状态（ACCEPTED），
 *   seq == lastSeq 返回 STALE_SEQ，seq < lastSeq 返回 OUTDATED_SEQ，
 *   因此乱序/迟到上报绝不可能让已确认状态回退。
 */
@Component
public class StateStore {

    /** 尝试确认一个已规范化属性值的结果。 */
    public enum ConfirmStatus { ACCEPTED, STALE_SEQ, OUTDATED_SEQ }

    public record ConfirmResult(ConfirmStatus status, DeviceState stateIfAccepted) {
    }

    /** 单台设备的可变状态与串行锁。 */
    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, DeviceState.ConfirmedProperty> props = new ConcurrentHashMap<>();
        /**
         * 动作回写专用的单调序号水位。命令副作用的序号空间与上报序号空间隔离，
         * 从保留基址起单调递增，永远高于正常上报序号，因此动作回写不会被误判为“乱序”。
         */
        private long commandSeqWatermark = COMMAND_SEQ_BASE;
    }

    /** 动作回写序号空间的保留基址（上报端序号不应达到该量级）。 */
    private static final long COMMAND_SEQ_BASE = 1_000_000_000L;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private Entry entry(String deviceId) {
        return entries.computeIfAbsent(deviceId, k -> new Entry());
    }

    /**
     * 比较 seq 后决定是否确认。调用方必须已完成设备注册与取值校验。
     *
     * @return ACCEPTED 时携带确认后的新快照；其余状态返回的快照为 null
     */
    public ConfirmResult confirm(String deviceId, String property, Object value, long seq, long confirmedAt) {
        Objects.requireNonNull(value, "value");
        Entry e = entry(deviceId);
        e.lock.lock();
        try {
            DeviceState.ConfirmedProperty current = e.props.get(property);
            if (current != null) {
                if (seq < current.lastSeq()) {
                    return new ConfirmResult(ConfirmStatus.OUTDATED_SEQ, null);
                }
                if (seq == current.lastSeq()) {
                    return new ConfirmResult(ConfirmStatus.STALE_SEQ, null);
                }
            }
            DeviceState.ConfirmedProperty next =
                    new DeviceState.ConfirmedProperty(value, seq, confirmedAt);
            e.props.put(property, next);
            return new ConfirmResult(ConfirmStatus.ACCEPTED, snapshot(deviceId, e));
        } finally {
            e.lock.unlock();
        }
    }

    /** 读取当前已确认状态快照（不加锁，ConcurrentHashMap 弱一致读足够用于条件判定）。 */
    public DeviceState snapshotOf(String deviceId) {
        Entry e = entries.get(deviceId);
        if (e == null) {
            return new DeviceState(deviceId, Map.of());
        }
        return snapshot(deviceId, e);
    }

    private DeviceState snapshot(String deviceId, Entry e) {
        return new DeviceState(deviceId, new java.util.LinkedHashMap<>(e.props));
    }

    /**
     * 动作下发成功后，将设备被改变的属性同步为“已确认”（动作副作用回写）。
     * 序号由存储内部单调派生（隔离的命令序号空间），调用方无需也不应提供业务序号。
     */
    public DeviceState applyCommand(String deviceId, String property, Object value, long confirmedAt) {
        Entry e = entry(deviceId);
        e.lock.lock();
        try {
            e.commandSeqWatermark++;
            e.props.put(property, new DeviceState.ConfirmedProperty(value, e.commandSeqWatermark, confirmedAt));
            return snapshot(deviceId, e);
        } finally {
            e.lock.unlock();
        }
    }

    /**
     * 补偿回滚专用的“比较并恢复”：仅当该属性当前仍停留在动作回写的那一版
     * （lastSeq 与动作回写时一致，即此后没有任何更新的确认状态进来）时，
     * 才把属性恢复为触发前原值；originalValue 为 null 表示触发前该属性不存在，
     * 恢复语义为移除该属性。
     *
     * 若动作之后已有更新的状态被确认（例如回滚途中又进来一条被确认的新上报/新动作），
     * 返回 false 且不做任何修改——回滚绝不能把已确认的新状态盖回旧值。
     *
     * @return true 表示已恢复为原值；false 表示已有更新确认状态，本次恢复被放弃
     */
    public boolean restoreOriginal(String deviceId, String property, long expectedSeq,
                                   Object originalValue, long confirmedAt) {
        Entry e = entry(deviceId);
        e.lock.lock();
        try {
            DeviceState.ConfirmedProperty current = e.props.get(property);
            if (current == null || current.lastSeq() != expectedSeq) {
                return false;
            }
            if (originalValue == null) {
                e.props.remove(property);
            } else {
                e.commandSeqWatermark++;
                e.props.put(property, new DeviceState.ConfirmedProperty(
                        originalValue, e.commandSeqWatermark, confirmedAt));
            }
            return true;
        } finally {
            e.lock.unlock();
        }
    }

    /** 测试/演示重置：清空全部已确认状态。 */
    public void reset() {
        entries.clear();
    }

    /** 仅供演示/测试使用的状态摘要。 */
    public List<DeviceState> allSnapshots() {
        List<DeviceState> out = new ArrayList<>();
        entries.forEach((id, e) -> out.add(snapshot(id, e)));
        return List.copyOf(out);
    }
}
