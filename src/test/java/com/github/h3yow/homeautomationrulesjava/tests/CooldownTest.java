package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.EngineClock;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.IngestOutcomeType;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.model.RuleSkipReason;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷却（最短触发间隔）专项测试。
 *
 * 计时全部通过注入式 {@link EngineClock} 固定/推进，确定性、无 sleep：
 * 冷却窗口起点为“实际触发的边沿评估时刻”，窗口内的 false→true 边沿一律挡下且不补发，
 * 窗口结束后必须重新出现一次 false→true 边沿才再次触发。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CooldownTest {

    private static final Logger log = LoggerFactory.getLogger(CooldownTest.class);

    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private RuleAdminService ruleAdmin;
    @Autowired
    private StateStore stateStore;
    @Autowired
    private RuleStore ruleStore;
    @Autowired
    private ExecutionLog executionLog;
    @Autowired
    private DeviceGateway gateway;
    @Autowired
    private ActionExecutor actionExecutor;
    @Autowired
    private EngineClock clock;

    private static final long T0 = 1_000_000L;
    private final AtomicLong idSeq = new AtomicLong(5000);

    @BeforeEach
    void resetRuntimeState() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        clock.setFixedNow(T0);
        idSeq.set(5000);
        // 恢复演示规则为：启用、阈值 30、仅一个动作（便于断言次数）、无冷却。
        if (!ruleAdmin.get("rule-cool-down").enabled()) {
            ruleAdmin.setEnabled("rule-cool-down", true);
        }
        ruleAdmin.updateDefinition("rule-cool-down", "高温自动断电",
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(new Action("turn-off-plug", "plug", "on", false)));
        ruleAdmin.setCooldownMillis("rule-cool-down", null);
    }

    @AfterEach
    void restoreClock() {
        clock.useSystemClock();
    }

    private String eid() {
        return "cooldown-test-event-" + idSeq.incrementAndGet();
    }

    private IngestResult temp(double value, long seq) {
        return ingestService.ingest("temp-sensor", "temperature", value, seq, eid(),
                100L + seq);
    }

    private long firedCount() {
        return executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down") && t.outcome().fired())
                .count();
    }

    private long plugCommandCount() {
        return executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).count();
    }

    private RuleFireOutcome outcomeOf(IngestResult r) {
        return r.ruleOutcomes().stream()
                .filter(o -> o.ruleId().equals("rule-cool-down")).findFirst().orElse(null);
    }

    @Test
    @DisplayName("抖动场景：冷却期内温度在阈值附近反复穿越，只允许触发一次；冷却后重新穿越才再次触发")
    void chatterCrossingsInsideCooldownAreSuppressed() {
        ruleAdmin.setCooldownMillis("rule-cool-down", 1000L);

        // t=T0：低温不满足。
        temp(25.0, 1);
        // t=T0：越过 30 -> 边沿触发（第 1 次），冷却窗口 [T0, T0+1000)。
        IngestResult fire1 = temp(31.0, 2);
        assertEquals(1, firedCount());

        // 模拟温度传感器在阈值附近来回抖动：高->低->高 多次穿越，全部发生在冷却期内。
        clock.advance(10);
        temp(20.0, 3);    // 离开
        IngestResult blocked1 = temp(32.0, 4);   // 再次进入但在冷却期 -> 挡下
        clock.advance(10);
        temp(21.0, 5);
        IngestResult blocked2 = temp(35.0, 6);   // 仍在冷却期 -> 挡下
        clock.advance(10);
        temp(22.0, 7);
        IngestResult blocked3 = temp(31.5, 8);   // 仍在冷却期 -> 挡下

        log.info("[抖动] 冷却期内被挡次数的 skip 判定: {}, {}, {}",
                outcomeOf(blocked1).skipReason(),
                outcomeOf(blocked2).skipReason(),
                outcomeOf(blocked3).skipReason());

        assertEquals(1, firedCount(), "冷却期内反复穿越不得重复触发");
        assertEquals(1, plugCommandCount(), "动作物理下发只允许一次");
        assertTrue(outcomeOf(blocked1).cooldownBlocked());
        assertTrue(outcomeOf(blocked2).cooldownBlocked());
        assertTrue(outcomeOf(blocked3).cooldownBlocked());
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, outcomeOf(blocked1).skipReason());
        assertNotNull(outcomeOf(blocked1).cooldownRemaining());
        assertTrue(outcomeOf(blocked1).cooldownRemaining() > 0);

        // 冷却刚结束、但条件一直满足期间的上报：不是新边沿 -> 不能触发（不补发）。
        clock.advance(970); // 到达 T0+1000（冷却结束边界）
        // 先让状态为“高”，再发一个仍为高的上报：条件持续满足。
        IngestResult stillHigh = temp(36.0, 9);
        assertFalse(stillHigh.ruleOutcomes().stream().anyMatch(RuleFireOutcome::fired));
        assertEquals(1, firedCount(), "冷却结束不会自动补发，必须等新的 false→true 边沿");

        // 离开后再次进入（冷却已过）-> 正常第 2 次触发。
        clock.advance(1);
        temp(20.0, 10);
        clock.advance(1);
        IngestResult fire2 = temp(33.0, 11);

        log.info("[抖动] 冷却后重新穿越 fired={} fireNo={}(期望2)",
                outcomeOf(fire2).fired(), outcomeOf(fire2).fireNo());
        assertTrue(outcomeOf(fire2).fired(), "冷却结束后新的 false→true 边沿必须正常触发，不得漏触发");
        assertEquals(2, outcomeOf(fire2).fireNo());
        assertEquals(2, firedCount(), "整个抖动过程恰好触发两次");
        assertEquals(2, plugCommandCount(), "每次实际触发对应一次动作下发");
    }

    @Test
    @DisplayName("冷却期内并发：多个离开/再进入事件几乎同时到达，被冷却挡下的边沿绝不产生额外动作")
    void concurrentCrossingsInsideCooldownFireNoExtraAction() throws Exception {
        ruleAdmin.setCooldownMillis("rule-cool-down", 5_000L);

        // 先触发一次，进入冷却；再确定性地“离开”一次，把边沿状态置为 false。
        temp(25.0, 1);
        temp(31.0, 2);
        assertEquals(1, firedCount());
        temp(20.0, 99);

        // 冷却期内：多线程并发制造“低/高”交替上报（不同 seq、不同 eventId）。
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        AtomicLong seq = new AtomicLong(100);
        AtomicLong lowHigh = new AtomicLong(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long s = seq.getAndIncrement();
                    // 一半上报低温（离开），一半上报高温（尝试再进入），乱序并发。
                    double v = lowHigh.getAndIncrement() % 2 == 0 ? 20.0 : 33.0;
                    results.add(ingestService.ingest("temp-sensor", "temperature", v,
                            s, "concurrent-cooldown-" + s, 100L + s));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        long accepted = results.stream().filter(r -> r.outcome() == IngestOutcomeType.ACCEPTED).count();
        long blocked = results.stream()
                .filter(r -> r.ruleOutcomes().stream().anyMatch(RuleFireOutcome::cooldownBlocked))
                .count();
        log.info("[冷却并发] accepted={} cooldownBlocked={} firedTotal={}(期望1) actions={}(期望1)",
                accepted, blocked, firedCount(), plugCommandCount());

        assertEquals(1, firedCount(), "冷却期内并发穿越不得多触发");
        assertEquals(1, plugCommandCount(), "冷却期内并发不得多下发一次动作");
        assertTrue(blocked >= 1, "至少有一个高温再进入边沿应被明确标记为 COOLDOWN_ACTIVE");
    }

    @Test
    @DisplayName("运行中调整冷却：调长即时挡住、调短即时放行、关闭后恢复纯边沿、再开启即时生效，且各规则互不影响")
    void runtimeCooldownAdjustmentsTakeEffectImmediately() {
        // 1) 先开 1000ms，触发一次。
        ruleAdmin.setCooldownMillis("rule-cool-down", 1000L);
        temp(25.0, 1);
        temp(31.0, 2);
        assertEquals(1, firedCount());

        // 2) 调长到 10000ms：推进 200ms 后重新穿越，此刻相对新窗口仍在冷却 -> 挡下。
        ruleAdmin.setCooldownMillis("rule-cool-down", 10000L);
        clock.advance(200);
        temp(20.0, 3);
        IngestResult blockedByLonger = temp(31.0, 4);
        assertTrue(outcomeOf(blockedByLonger).cooldownBlocked(), "调长后应立即按更长窗口挡下");
        assertEquals(10000L, outcomeOf(blockedByLonger).cooldownMillis());

        // 3) 调短到 10ms：窗口从“上次实际触发时刻 T0”起算，现在 T0+200，早已超过 10ms。
        //    注意：挡下的边沿不补发；需要一次新的离开/进入。
        ruleAdmin.setCooldownMillis("rule-cool-down", 10L);
        temp(20.0, 5);
        IngestResult afterShorter = temp(31.0, 6);
        assertTrue(outcomeOf(afterShorter).fired(), "调短后新边沿应立即被放行");
        assertEquals(2, firedCount());

        // 4) 关闭冷却：立即连续穿越两次都应触发（纯边沿语义）。
        ruleAdmin.setCooldownMillis("rule-cool-down", null);
        assertNull(ruleAdmin.get("rule-cool-down").cooldownMillis());
        clock.advance(1);
        temp(20.0, 7);
        IngestResult noCd1 = temp(31.0, 8);
        clock.advance(1);
        temp(20.0, 9);
        IngestResult noCd2 = temp(31.0, 10);
        assertTrue(outcomeOf(noCd1).fired() && outcomeOf(noCd2).fired(),
                "关闭冷却后行为应与历史边沿触发完全一致");
        assertEquals(4, firedCount());

        // 5) 重新开启：即时生效。最近一次实际触发时刻很近，下一次穿越应被挡。
        ruleAdmin.setCooldownMillis("rule-cool-down", 10000L);
        temp(20.0, 11);
        IngestResult reEnabled = temp(31.0, 12);
        assertTrue(outcomeOf(reEnabled).cooldownBlocked(), "重新开启冷却应立即生效");
        assertEquals(4, firedCount());
    }

    @Test
    @DisplayName("不配置冷却（默认）：行为与历史完全一致，结果中不带冷却信息")
    void noCooldownMeansLegacyEdgeOnlyBehavior() {
        assertNull(ruleAdmin.get("rule-cool-down").cooldownMillis(), "演示规则默认无冷却");

        temp(25.0, 1);
        IngestResult edge = temp(31.0, 2);
        IngestResult still1 = temp(32.0, 3);
        IngestResult still2 = temp(33.0, 4);
        temp(20.0, 5);
        IngestResult edge2 = temp(34.0, 6);

        assertEquals(2, firedCount());
        assertEquals(2, plugCommandCount());
        assertTrue(outcomeOf(edge).fired());
        assertTrue(outcomeOf(edge2).fired());
        // 持续满足：可区分原因为 STILL_SATISFIED，而不是 COOLDOWN_ACTIVE。
        assertEquals(RuleSkipReason.STILL_SATISFIED, outcomeOf(still1).skipReason());
        assertEquals(RuleSkipReason.STILL_SATISFIED, outcomeOf(still2).skipReason());
        assertNull(outcomeOf(still1).cooldownMillis());
        assertNull(outcomeOf(still1).cooldownRemaining());
        assertFalse(outcomeOf(still1).cooldownBlocked());
    }

    @Test
    @DisplayName("可解释性与追溯：被冷却挡下 vs 条件持续满足 vs 条件没到，三类结论在事件结果与轨迹中可区分")
    void blockedVsStillSatisfiedVsNotReachedAreDistinguishableInTraces() {
        ruleAdmin.setCooldownMillis("rule-cool-down", 1000L);

        // 条件没到：不满足时无规则结果。
        IngestResult notReached = temp(20.0, 1);
        assertTrue(notReached.ruleOutcomes().isEmpty(), "条件不满足不应产生规则记录");
        assertTrue(notReached.decision().contains("条件未到"));

        temp(31.0, 2); // 触发
        // 新边沿：先离开再进入（在冷却期内）。
        temp(20.0, 3);
        clock.advance(5);
        IngestResult blocked = temp(32.0, 4);
        RuleFireOutcome blockedOutcome = outcomeOf(blocked);

        // 持续满足：冷却期内保持高温的上报。
        IngestResult still = temp(37.0, 5);
        RuleFireOutcome stillOutcome = outcomeOf(still);

        log.info("[可解释] blocked.summary={}", blockedOutcome.summary());
        log.info("[可解释] still.summary={}", stillOutcome.summary());

        assertTrue(blocked.decision().contains("边沿被冷却挡下"));
        assertTrue(blockedOutcome.cooldownBlocked());
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, blockedOutcome.skipReason());
        assertEquals(RuleSkipReason.STILL_SATISFIED, stillOutcome.skipReason());

        // 轨迹侧：/api/traces/fires 同源数据中两类 skip 都可按原因过滤区分。
        long cooldownTraces = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down"))
                .filter(t -> t.outcome().cooldownBlocked()).count();
        long stillTraces = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down"))
                .filter(t -> t.outcome().skipReason() == RuleSkipReason.STILL_SATISFIED).count();
        assertTrue(cooldownTraces >= 1, "轨迹中必须能查出被冷却挡下的边沿");
        assertTrue(stillTraces >= 1, "轨迹中必须能查出持续满足的非边沿结论");
    }

    @Test
    @DisplayName("冷却不影响失败补偿一致性：序列中途失败回滚后，设备状态/动作记录/最终结论三者一致")
    void cooldownDoesNotBreakCompensationConsistency() {
        // 不设冷却；给演示规则加回第二个会永久超时的动作 lock-door。
        ruleAdmin.updateDefinition("rule-cool-down", "高温自动断电",
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(
                        new Action("turn-off-plug", "plug", "on", false),
                        new Action("lock-door", "lock", "locked", true)));
        // 触发前 plug 已知为开（补偿基线）。
        gateway.sendCommand("bootstrap-plug", "plug", "on", true);
        stateStore.applyCommand("plug", "on", true, 90L);
        gateway.failPermanently("lock", "locked",
                com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason.EXECUTION_TIMEOUT);

        temp(25.0, 1);
        temp(32.0, 2);

        RuleFireOutcome fire = executionLog.fireTraces().stream()
                .filter(t -> t.outcome().fired()).findFirst().orElseThrow().outcome();

        var plugRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();
        var lockRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("lock-door")).findFirst().orElseThrow();

        // 三者一致：结论说 PARTIAL-FAILURE 且已回滚；记录里 plug 标 compensated；物理状态回滚为 true。
        assertTrue(fire.partiallyFailed(), "最终结论必须是部分失败");
        assertTrue(plugRec.compensated(), "动作记录必须显示 plug 已补偿");
        assertFalse(lockRec.compensated(), "失败动作本身不补偿");
        assertEquals(Boolean.TRUE, gateway.physicalValue("plug", "on"),
                "设备状态必须与结论一致：plug 已回滚到触发前原值 true");
        assertEquals(null, gateway.physicalValue("lock", "locked"),
                "lock 从未成功，物理侧无残留");
        assertTrue(fire.summary().contains("rolled back to pre-fire state"),
                "最终结论文案必须明确说明已回滚");

        // 轨迹中的同一条记录也必须带 compensated，保证“查到的记录”和结论一致。
        long compensatedInLog = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug"))
                .filter(r -> r.compensated()).count();
        assertEquals(1, compensatedInLog, "追溯记录与最终结论一致：恰好一条已补偿的 plug 记录");
    }
}
