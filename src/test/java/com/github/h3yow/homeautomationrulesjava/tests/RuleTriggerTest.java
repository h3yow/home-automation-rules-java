package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.IngestOutcomeType;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.BeforeEach;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则触发测试：边沿触发、持续满足不重复、动作序列只下发一次、运行中启停与调参即时生效。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RuleTriggerTest {

    private static final Logger log = LoggerFactory.getLogger(RuleTriggerTest.class);

    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private RuleAdminService ruleAdmin;
    @Autowired
    private ExecutionLog executionLog;
    @Autowired
    private DeviceGateway gateway;
    @Autowired
    private StateStore stateStore;
    @Autowired
    private RuleStore ruleStore;
    @Autowired
    private ActionExecutor actionExecutor;

    @BeforeEach
    void resetRuntimeState() {
        // 保留演示规则定义，仅重置运行时状态，保证测试方法间完全隔离、顺序无关。
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        // 恢复演示规则：启用 + 阈值 30 + 两个动作。
        if (!ruleAdmin.get("rule-cool-down").enabled()) {
            ruleAdmin.setEnabled("rule-cool-down", true);
        }
        ruleAdmin.updateDefinition("rule-cool-down", "高温自动断电",
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(
                        new Action("turn-off-plug", "plug", "on", false),
                        new Action("lock-door", "lock", "locked", true)));
    }

    private final AtomicLong idSeq = new AtomicLong(1000);

    private String eid() {
        return "rule-test-event-" + idSeq.incrementAndGet();
    }

    private IngestResult temp(double value, long seq) {
        log.info("[输入事件] temp-sensor.temperature={} seq={}", value, seq);
        return ingestService.ingest("temp-sensor", "temperature", value, seq, eid(),
                System.currentTimeMillis() + seq);
    }

    @Test
    @DisplayName("边沿触发一次；条件持续满足期间重复上报不重复触发；离开后再进入才再次触发")
    void edgeTriggerOnlyOnceAndReArmAfterLeaving() {
        // seq 1: 不满足（25 <= 30）
        IngestResult r1 = temp(25.0, 1);
        // seq 2: 越过阈值（边沿 false->true）应触发一次
        IngestResult r2 = temp(31.0, 2);
        // seq 3/4: 条件持续满足（值变化但仍 >30），不得重复触发
        IngestResult r3 = temp(32.0, 3);
        IngestResult r4 = temp(33.0, 4);
        // seq 5: 回到不满足，重置边沿
        IngestResult r5 = temp(20.0, 5);
        // seq 6: 再次越过阈值，应第二次触发
        IngestResult r6 = temp(35.0, 6);

        long fired = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down") && t.outcome().fired())
                .count();

        log.info("[判定依据] fired 次数={} (期望 2); r2触发={}, r3触发={}, r4触发={}, r6触发={}",
                fired, fired(r2), fired(r3), fired(r4), fired(r6));

        assertFalse(fired(r1), "未满足时不应触发");
        assertTrue(fired(r2), "越过阈值边沿必须触发");
        assertFalse(fired(r3), "持续满足不得重复触发");
        assertFalse(fired(r4), "持续满足不得重复触发");
        assertFalse(fired(r5), "离开条件不触发动作");
        assertTrue(fired(r6), "重新进入应再次触发");
        assertEquals(2, fired, "整个过程规则只允许边沿触发两次");

        // 第一次触发的动作序列：plug.on=false 与 lock.locked=true 各只下发一次物理副作用。
        Object plugOn = gateway.physicalValue("plug", "on");
        Object locked = gateway.physicalValue("lock", "locked");
        log.info("[状态断言] 物理侧 plug.on={} (期望 false), lock.locked={} (期望 true)", plugOn, locked);
        assertEquals(false, plugOn);
        assertEquals(true, locked);
    }

    @Test
    @DisplayName("运行中停用规则立即不触发；重新启用后边沿语义仍然成立")
    void runtimeDisableEnableTakesEffectImmediately() {
        long versionBefore = ruleAdmin.get("rule-cool-down").version();
        Rule disabled = ruleAdmin.setEnabled("rule-cool-down", false);
        assertFalse(disabled.enabled());
        assertEquals(versionBefore + 1, disabled.version(), "停用产生新版本");

        IngestResult r = temp(40.0, 20);
        assertFalse(fired(r), "停用期间到达的事件不得触发");
        log.info("[判定依据] 停用期间 40 度事件触发={} (期望 false), decision={}", fired(r), r.decision());

        ruleAdmin.setEnabled("rule-cool-down", true);
        // 条件此刻已满足：启用后下一个“确认事件”评估时边沿如何定义？
        // 启用前没有边沿状态，首次评估即从 false->true，应触发（可预期、可解释）。
        IngestResult r2 = temp(41.0, 21);
        assertTrue(fired(r2), "重新启用后越过/保持高位事件应能触发一次");
        log.info("[判定依据] 重新启用后事件触发={} fireNo={}", fired(r2), fireOf(r2));
    }

    @Test
    @DisplayName("运行中参数调整即时生效：阈值从 30 改为 25")
    void runtimeParameterUpdateTakesEffectImmediately() {
        ruleAdmin.updateDefinition("rule-cool-down", "高温自动断电",
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 25)),
                List.of(new Action("turn-off-plug", "plug", "on", false)));

        IngestResult r = temp(26.0, 30);
        log.info("[判定依据] 阈值调整为25后, 26度事件触发={} (期望 true)", fired(r));
        assertTrue(fired(r), "参数调整必须对新事件立即生效");

        IngestResult rLow = temp(10.0, 31);
        IngestResult r24 = temp(24.0, 32);
        assertFalse(fired(r24), "24 <= 25 不应触发");
    }

    private boolean fired(IngestResult r) {
        return r.ruleOutcomes().stream().anyMatch(RuleFireOutcome::fired);
    }

    private long fireOf(IngestResult r) {
        return r.ruleOutcomes().stream()
                .filter(RuleFireOutcome::fired)
                .findFirst().map(RuleFireOutcome::fireNo).orElse(-1L);
    }
}
