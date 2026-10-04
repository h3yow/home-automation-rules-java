package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * - 每个属性独立记录上报高水位 lastReportSeq：只有 seq &gt; lastReportSeq 才推进状态（ACCEPTED），
 *   seq == lastReportSeq 返回 STALE_SEQ，seq &lt; lastReportSeq 返回 OUTDATED_SEQ，
 *   因此乱序/迟到上报绝不可能让已确认状态回退；
 * - 上报序号空间与动作回写完全隔离：动作下发/回滚回写不占用上报序号，设备在动作之后产生的
 *   真实上报不会因为“序号小于命令水位”被误判为乱序丢弃；
 * - 每个属性额外维护 reportEpoch（已确认上报纪元）：只被“上报确认”推进，动作回写不变更它。
 *   回滚时按触发前快照中的 reportEpoch 做比较并恢复（CAS 语义）：触发后若已有新的上报被确认，
 *   回滚一律跳过，绝不把已确认的新状态盖回旧值。
 */
@Component
public class StateStore {

    /** 尝试确认一个已规范化属性值的结果。 */
    public enum ConfirmStatus { ACCEPTED, STALE_SEQ, OUTDATED_SEQ }

    /** 回滚（恢复触发前原值）的结果。 */
    public enum RestoreStatus {
        /** 已成功恢复：物理侧命令下发成功，查询侧状态也已同步为触发前原值（或属性被移除）。 */
        RESTORED,
        /** 触发后已有新的上报被确认：本次回滚跳过，状态保留为那个更新的已确认值。 */
        SUPERSEDED_BY_NEW_REPORT,
        /** 物理侧回滚命令失败：查询侧状态不变，调用方必须按“未恢复”上报结论。 */
        FAILED
    }

    public record ConfirmResult(ConfirmStatus status, DeviceState stateIfAccepted) {
    }

    public record RestoreResult(RestoreStatus status, DeviceState stateAfterRestore) {
        public boolean restored() {
            return status == RestoreStatus.RESTORED;
        }

        public boolean superseded() {
            return status == RestoreStatus.SUPERSEDED_BY_NEW_REPORT;
        }
    }

    /** 单台设备的可变状态与串行锁。 */
    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    }

    /**
     * 单个属性的运行时槽位。present=false 表示触发前该属性本来就没有值（回滚恢复“缺失”时用），
     * 但仍保留 reportEpoch/lastReportSeq，使得“缺失后又有上报进来”的 CAS 判定可区分。
     */
    private static final class Slot {
        private boolean present;
        private Object value;
        private long lastReportSeq;
        private long reportEpoch;
        private long confirmedAt;
    }

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
            Slot s = e.slots.get(property);
            if (s != null && s.present) {
                if (seq < s.lastReportSeq) {
                    return new ConfirmResult(ConfirmStatus.OUTDATED_SEQ, null);
                }
                if (seq == s.lastReportSeq) {
                    return new ConfirmResult(ConfirmStatus.STALE_SEQ, null);
                }
            }
            if (s == null) {
                s = new Slot();
                e.slots.put(property, s);
            }
            s.present = true;
            s.value = value;
            s.lastReportSeq = seq;
            s.reportEpoch++;
            s.confirmedAt = confirmedAt;
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
        Map<String, DeviceState.ConfirmedProperty> props = new LinkedHashMap<>();
        e.slots.forEach((name, s) -> {
            if (s.present) {
                props.put(name, new DeviceState.ConfirmedProperty(
                        s.value, s.lastReportSeq, s.confirmedAt, s.reportEpoch));
            }
        });
        return new DeviceState(deviceId, props);
    }

    /**
     * 动作下发成功后，将设备被改变的属性同步为“已确认”（动作副作用回写）。
     * 不推进上报高水位与 reportEpoch：动作回写与上报序号空间隔离。
     */
    public DeviceState applyCommand(String deviceId, String property, Object value, long confirmedAt) {
        Entry e = entry(deviceId);
        e.lock.lock();
        try {
            Slot s = e.slots.get(property);
            if (s == null) {
                s = new Slot();
                e.slots.put(property, s);
            }
            s.present = true;
            s.value = value;
            s.confirmedAt = confirmedAt;
            // lastReportSeq / reportEpoch 保持不变：命令不产生上报纪元。
            return snapshot(deviceId, e);
        } finally {
            e.lock.unlock();
        }
    }

    /**
     * 回滚恢复（CAS 语义）：仅当“触发之后没有新的上报被确认”时，才把属性恢复为触发前原值。
     *
     * <p>在设备锁内先做纪元判定再执行物理回调，保证与并发上报线性一致：
     * 若回滚先拿到锁，则晚到的上报在回滚完成后才确认，最终状态以新上报为准；
     * 若上报先确认，则纪元不匹配，回滚整体跳过（物理侧也不下发），绝不覆盖新状态。</p>
     *
     * @param expectedReportEpoch 触发前快照中该属性的 reportEpoch（属性缺失时为 0）
     * @param originalPresent     触发前该属性是否有值
     * @param originalValue       触发前原值（originalPresent=false 时忽略）
     * @param physicalRestore     物理侧恢复动作；originalPresent=false 时应“清除/复位”物理属性
     * @return 恢复结果与恢复后的状态快照（SUPERSEDED/FAILED 时快照为当前状态）
     */
    public RestoreResult restoreIfNoNewReport(String deviceId, String property,
                                              long expectedReportEpoch, boolean originalPresent,
                                              Object originalValue, long confirmedAt,
                                              Runnable physicalRestore) {
        Entry e = entry(deviceId);
        e.lock.lock();
        try {
            Slot s = e.slots.get(property);
            long epoch = s == null ? 0L : s.reportEpoch;
            if (epoch != expectedReportEpoch) {
                return new RestoreResult(RestoreStatus.SUPERSEDED_BY_NEW_REPORT, snapshot(deviceId, e));
            }
            try {
                physicalRestore.run();
            } catch (RuntimeException ex) {
                // 物理回滚失败：查询侧状态保持失败前的值，结论必须按“未恢复”呈现。
                return new RestoreResult(RestoreStatus.FAILED, snapshot(deviceId, e));
            }
            if (s == null) {
                s = new Slot();
                e.slots.put(property, s);
            }
            if (originalPresent) {
                s.present = true;
                s.value = originalValue;
                s.confirmedAt = confirmedAt;
                // lastReportSeq / reportEpoch 保持不变：回滚恢复的是触发前原值，不产生新上报。
            } else {
                // 恢复“触发前本来就没有值”：查询侧同样移除该属性。
                s.present = false;
                s.value = null;
                s.confirmedAt = confirmedAt;
            }
            return new RestoreResult(RestoreStatus.RESTORED, snapshot(deviceId, e));
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
