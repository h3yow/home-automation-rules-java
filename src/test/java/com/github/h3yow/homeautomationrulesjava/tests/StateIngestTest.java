package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.model.IngestOutcomeType;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.RejectReason;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 上报一致性测试：重复、乱序/迟到、同序号重复、未注册设备、非法取值、缺字段。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StateIngestTest {

    private static final Logger log = LoggerFactory.getLogger(StateIngestTest.class);

    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private DeviceRegistry deviceRegistry;
    @Autowired
    private StateStore stateStore;
    @Autowired
    private RuleStore ruleStore;
    @Autowired
    private ExecutionLog executionLog;
    @Autowired
    private DeviceGateway gateway;

    private final AtomicLong idSeq = new AtomicLong();

    private String eid() {
        return "test-event-" + idSeq.incrementAndGet();
    }

    @BeforeEach
    void resetContext() {
        // Spring 上下文在测试类间共享，这里把演示规则停用，避免干扰本类的纯上报断言。
        if (ruleStore.get("rule-cool-down") != null && ruleStore.get("rule-cool-down").enabled()) {
            // 通过 store 直接替换为停用版本（测试隔离）。
            ruleStore.replace(ruleStore.get("rule-cool-down").withEnabled(false));
        }
        gateway.reset();
    }

    @Test
    @DisplayName("乱序/迟到上报不得让已确认状态回退；重复上报幂等忽略")
    void outOfOrderAndDuplicateNeverRollsBack() {
        String device = "temp-sensor";
        long now = System.currentTimeMillis();

        // 先到 seq=3（高水位）
        IngestResult r3 = ingest(device, 25.0, 3L, now + 3);
        // 迟到 seq=1
        IngestResult r1 = ingest(device, 40.0, 1L, now + 1);
        // 乱序 seq=2
        IngestResult r2 = ingest(device, 35.0, 2L, now + 2);
        // 完全重复投递（同 eventId）
        IngestResult r3Dup = ingestService.ingest(device, "temperature", 25.0, 3L, r3.eventId(), now + 3);
        // 同序号 seq=3 但不同 eventId（重复上报同一序号）
        IngestResult r3SameSeq = ingest(device, 99.0, 3L, now + 4);

        assertEquals(IngestOutcomeType.ACCEPTED, r3.outcome(), r3.decision());
        assertEquals(IngestOutcomeType.IGNORED, r1.outcome(), r1.decision());
        assertEquals(IngestOutcomeType.IGNORED, r2.outcome(), r2.decision());
        assertEquals(IngestOutcomeType.IGNORED, r3Dup.outcome(), r3Dup.decision());
        assertEquals(IngestOutcomeType.IGNORED, r3SameSeq.outcome(), r3SameSeq.decision());

        // 已确认状态始终停留在 seq=3 的 25.0，绝不回退。
        Object confirmed = stateStore.snapshotOf(device).valueOf("temperature");
        assertEquals(25.0, ((Number) confirmed).doubleValue(), 0.001,
                "迟到/乱序上报不得回退已确认状态");

        // 推进到 seq=4 才允许更新。
        IngestResult r4 = ingest(device, 26.5, 4L, now + 5);
        assertEquals(IngestOutcomeType.ACCEPTED, r4.outcome(), r4.decision());
        assertEquals(26.5, ((Number) stateStore.snapshotOf(device).valueOf("temperature")).doubleValue(), 0.001);
    }

    @Test
    @DisplayName("未注册设备/非法取值/结构缺失必须被拒绝且原因可区分")
    void invalidReportsAreRejectedWithDistinctReasons() {
        long now = System.currentTimeMillis();

        IngestResult unknownDevice = ingestService.ingest(
                "no-such-device", "temperature", 10, 1L, eid(), now);
        IngestResult unknownProperty = ingestService.ingest(
                "temp-sensor", "humidity", 10, 1L, eid(), now);
        IngestResult outOfRange = ingestService.ingest(
                "temp-sensor", "temperature", 200, 1L, eid(), now);
        IngestResult wrongType = ingestService.ingest(
                "temp-sensor", "temperature", "not-a-number", 1L, eid(), now);
        IngestResult malformed = ingestService.ingest(
                "temp-sensor", "temperature", null, null, null, null);

        log.info("[断言] 拒绝原因分别为: unknownDevice={}, unknownProperty={}, outOfRange={}, wrongType={}, malformed={}",
                unknownDevice.rejectReason(), unknownProperty.rejectReason(),
                outOfRange.rejectReason(), wrongType.rejectReason(), malformed.rejectReason());

        assertEquals(RejectReason.UNKNOWN_DEVICE, unknownDevice.rejectReason());
        assertEquals(RejectReason.INVALID_VALUE, unknownProperty.rejectReason());
        assertEquals(RejectReason.INVALID_VALUE, outOfRange.rejectReason());
        assertEquals(RejectReason.INVALID_VALUE, wrongType.rejectReason());
        assertEquals(RejectReason.MALFORMED_EVENT, malformed.rejectReason());

        // 被拒绝的上报不改变状态。
        assertEquals(IngestOutcomeType.REJECTED, unknownDevice.outcome());
        assertNotNull(deviceRegistry.find("temp-sensor"));
    }

    private IngestResult ingest(String device, double value, long seq, long eventTime) {
        return ingestService.ingest(device, "temperature", value, seq, eid(), eventTime);
    }
}
