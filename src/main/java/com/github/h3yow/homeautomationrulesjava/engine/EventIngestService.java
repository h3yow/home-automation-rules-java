package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.IgnoreReason;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.model.RejectReason;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.model.StateEvent;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件接入服务：所有上报的唯一入口，定义乱序/重复/并发下的统一结论。
 *
 * 处理顺序（短路）：
 * 1. 结构校验：缺字段 -> REJECTED / MALFORMED_EVENT；
 * 2. 设备校验：未注册 -> REJECTED / UNKNOWN_DEVICE；
 * 3. 属性与取值校验：属性不存在或越界/类型错 -> REJECTED / INVALID_VALUE；
 * 4. eventId 幂等：同一事件重复投递 -> IGNORED / DUPLICATE_EVENT（绝不重复触发规则）；
 * 5. 序号单调确认：旧序号 -> IGNORED / OUTDATED_SEQ；同序号不同事件 -> STALE_SEQ；
 * 6. ACCEPTED：推进已确认状态，随后由 RuleEngine 做（带边沿与每规则锁的）规则评估。
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final DeviceRegistry deviceRegistry;
    private final StateStore stateStore;
    private final RuleEngine ruleEngine;
    private final ExecutionLog executionLog;

    /** 已处理 eventId（重复投递直接忽略）。 */
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public EventIngestService(DeviceRegistry deviceRegistry, StateStore stateStore,
                              RuleEngine ruleEngine, ExecutionLog executionLog) {
        this.deviceRegistry = deviceRegistry;
        this.stateStore = stateStore;
        this.ruleEngine = ruleEngine;
        this.executionLog = executionLog;
    }

    /** 供 API/测试直接传入字段；在此完成结构校验并组装 StateEvent。 */
    public IngestResult ingest(String deviceId, String property, Object rawValue,
                               Long seq, String eventId, Long eventTime) {
        // 1) 结构校验（可区分 MALFORMED_EVENT）。
        if (isBlank(deviceId) || isBlank(property) || seq == null || isBlank(eventId)
                || eventTime == null || rawValue == null) {
            IngestResult r = IngestResult.rejected(String.valueOf(eventId), RejectReason.MALFORMED_EVENT,
                    "事件结构非法: deviceId/property/seq/eventId/eventTime/value 均不可为空，"
                            + "实际 deviceId=" + deviceId + ", property=" + property
                            + ", seq=" + seq + ", eventId=" + eventId + ", eventTime=" + eventTime);
            log.info("收到上报[结构非法] raw=deviceId={}, property={}, seq={}, eventId={}, value={}",
                    deviceId, property, seq, eventId, rawValue);
            log.info("判定依据: {}", r.decision());
            return r;
        }
        return ingest(new StateEvent(deviceId, property, rawValue, seq, eventId, eventTime));
    }

    public IngestResult ingest(StateEvent event) {
        log.info("收到上报 eventId={} device={}.{} seq={} eventTime={} value={}",
                event.eventId(), event.deviceId(), event.property(), event.seq(),
                event.eventTime(), event.rawValue());

        IngestResult result = doIngest(event);

        log.info("判定结果 eventId={} outcome={} reject={} ignore={} decision={}",
                event.eventId(), result.outcome(), result.rejectReason(),
                result.ignoreReason(), result.decision());
        if (result.outcome() == com.github.h3yow.homeautomationrulesjava.model.IngestOutcomeType.ACCEPTED) {
            for (RuleFireOutcome o : result.ruleOutcomes()) {
                log.info("  规则结果 rule={} fired={} fireNo={} skipReason={} cooldownRemaining={} summary={}",
                        o.ruleId(), o.fired(), o.fireNo(), o.skipReason(),
                        o.cooldownRemaining(), o.summary().replace("\n", " | "));
            }
        }
        executionLog.appendEvent(event.deviceId(), event.property(), event.seq(), event.eventId(), result);
        return result;
    }

    private IngestResult doIngest(StateEvent event) {
        // 2) 设备校验。
        Device device = deviceRegistry.find(event.deviceId());
        if (device == null) {
            return IngestResult.rejected(event.eventId(), RejectReason.UNKNOWN_DEVICE,
                    "设备未注册: " + event.deviceId() + "，操作被拒绝");
        }

        // 3) 属性存在性与取值校验（规范化；非法即拒绝，状态不变）。
        PropertySpec spec = device.specOf(event.property());
        if (spec == null) {
            return IngestResult.rejected(event.eventId(), RejectReason.INVALID_VALUE,
                    "设备 " + event.deviceId() + " 不存在属性 " + event.property());
        }
        final Object normalized;
        try {
            normalized = spec.normalize(event.rawValue());
        } catch (IllegalArgumentException ex) {
            return IngestResult.rejected(event.eventId(), RejectReason.INVALID_VALUE,
                    "取值非法被拒绝: " + ex.getMessage());
        }

        // 4) eventId 幂等：重复投递（含并发重复）只可能有一个线程通过 add。
        if (!processedEventIds.add(event.eventId())) {
            return IngestResult.ignored(event.eventId(), IgnoreReason.DUPLICATE_EVENT,
                    "重复上报:eventId=" + event.eventId() + " 已处理，幂等忽略，不重复触发规则");
        }

        // 5) 序号单调确认（同设备同属性串行）。
        StateStore.ConfirmResult confirm = stateStore.confirm(
                event.deviceId(), event.property(), normalized, event.seq(), System.currentTimeMillis());
        switch (confirm.status()) {
            case OUTDATED_SEQ -> {
                return IngestResult.ignored(event.eventId(), IgnoreReason.OUTDATED_SEQ,
                        "乱序/迟到上报:seq=" + event.seq() + " 小于已确认序号，已确认状态保持不回退");
            }
            case STALE_SEQ -> {
                return IngestResult.ignored(event.eventId(), IgnoreReason.STALE_SEQ,
                        "同序号重复上报:seq=" + event.seq() + " 已确认，幂等忽略");
            }
            case ACCEPTED -> {
                // 6) 状态确认成功 -> 评估规则（边沿触发 + 每规则锁，保证并发只触发一次）。
                List<RuleFireOutcome> outcomes = ruleEngine.onStateConfirmed();
                String decision = buildAcceptedDecision(event, normalized, outcomes);
                return IngestResult.accepted(event.eventId(), normalized, decision, outcomes);
            }
            default -> throw new IllegalStateException("unknown confirm status: " + confirm.status());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 组装 ACCEPTED 事件的自然语言结论：
     * 实际触发的规则与“边沿被冷却挡下 / 条件持续满足”的规则分别列出，
     * 让排查时能一眼区分“条件没到 / 被冷却挡住 / 正常触发”。
     */
    private static String buildAcceptedDecision(StateEvent event, Object normalized,
                                                List<RuleFireOutcome> outcomes) {
        StringBuilder sb = new StringBuilder("接受上报并确认状态:")
                .append(event.deviceId()).append('.').append(event.property())
                .append('=').append(normalized).append(" seq=").append(event.seq());
        if (outcomes.isEmpty()) {
            return sb.append("；无规则边沿相关结论（条件未到）").toString();
        }
        List<String> fired = outcomes.stream().filter(RuleFireOutcome::fired)
                .map(o -> o.ruleId() + "(fireNo=" + o.fireNo() + ")").toList();
        List<String> cooldownBlocked = outcomes.stream().filter(RuleFireOutcome::cooldownBlocked)
                .map(o -> o.ruleId() + "(剩余约" + o.cooldownRemaining() + "ms)").toList();
        List<String> stillSatisfied = outcomes.stream()
                .filter(o -> !o.fired() && !o.cooldownBlocked())
                .map(RuleFireOutcome::ruleId).toList();
        if (!fired.isEmpty()) {
            sb.append("；触发规则 ").append(fired);
        }
        if (!cooldownBlocked.isEmpty()) {
            sb.append("；边沿被冷却挡下 ").append(cooldownBlocked);
        }
        if (!stillSatisfied.isEmpty()) {
            sb.append("；条件持续满足不重复触发 ").append(stillSatisfied);
        }
        return sb.toString();
    }

    /** 测试/演示重置：清空已处理 eventId 幂等集合。 */
    public void clearProcessedEventIds() {
        processedEventIds.clear();
    }
}
