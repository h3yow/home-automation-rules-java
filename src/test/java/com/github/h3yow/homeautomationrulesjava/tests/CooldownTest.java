package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleClock;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷却（最短触发间隔）测试。
 *
 * 计时口径：全部用例通过 {@link RuleClock} 固定处理时刻，使冷却判定与墙钟无关、可重复；
 * 事件自带 eventTime 仍可乱序，但冷却只看处理时刻。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CooldownTest {

    private static final Logger log = LoggerFactory.getLogger(CooldownTest.class);
    private static final String RULE = "rule-cool-down";

    @Autowired private EventIngestService ingestService;
    @Autowired private RuleAdminService ruleAdmin;
    @Autowired private RuleStore ruleStore;
    @Autowired private StateStore stateStore;
    @Autowired private ExecutionLog executionLog;
    @Autowired private DeviceGateway gateway;
    @Autowired private ActionExecutor actionExecutor;
    @Autowired private RuleClock clock;

    private final AtomicLong seqGen = new AtomicLong();

    @BeforeEach
    void reset() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        clock.setFixed(1_000L);
        // 默认关闭冷却，各用例按需再开。
        ruleAdmin.setCooldown(RULE, 0L);
        ruleAdmin.setEnabled(RULE, true);
        seqGen.set(0);
    }

    @AfterEach
    void restoreClock() {
        clock.resetToSystem();
    }

    private IngestResult temp(double value) {
        long seq = seqGen.incrementAndGet();
        return ingestService.ingest("temp-sensor", "temperature", value, seq,
                "cooldown-event-" + seq, 10_000L + seq);
    }

    private long firedCount() {
        return executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals(RULE) && t.outcome().fired())
                .count();
    }

    private long suppressedCount() {
        return executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals(RULE) && t.outcome().suppressedByCooldown())
                .count();
    }

    private boolean fired(IngestResult r) {
        return r.ruleOutcomes().stream().anyMatch(RuleFireOutcome::fired);
    }

    private RuleFireOutcome outcomeOf(IngestResult r) {
        return r.ruleOutcomes().stream().filter(o -> o.ruleId().equals(RULE)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("不配置冷却（默认 cooldownMillis=0）：多次 false→true 穿越每次都触发，行为与旧版本完全一致")
    void noCooldownPreservesLegacyBehavior() {
        IngestResult a = temp(35);   // 边沿 1
        IngestResult b = temp(20);   // 离开
        IngestResult c = temp(36);   // 边沿 2
        IngestResult d = temp(21);   // 离开
        IngestResult e = temp(37);   // 边沿 3（同一处理时刻也不影响，无冷却即不看时间）

        log.info("[默认行为] fired a/c/e = {}/{}/{} 总触发={}(期望3)", fired(a), fired(c), fired(e), firedCount());
        assertEquals(0L, ruleAdmin.get(RULE).cooldownMillis());
        assertTrue(fired(a));
        assertTrue(fired(c));
        assertTrue(fired(e));
        assertEquals(3, firedCount());
        // 每次触发都真实下发了一次动作序列。
        assertEquals(3, executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).count());
    }

    @Test
    @DisplayName("抖动场景：冷却窗口内温度在阈值附近来回穿越，只触发一次；窗口结束后下一次边沿正常触发")
    void jitterAroundThresholdDuringCooldownFiresOnceThenRearms() {
        ruleAdmin.setCooldown(RULE, 1_000L);

        // t=1000：首次越过阈值，触发（基准时刻 lastFiredAt=1000）。
        IngestResult first = temp(31);
        // 冷却窗口 [1000,2000) 内连续抖动：离开再进入多次，边沿全部被冷却挡下。
        IngestResult low1 = temp(29);
        IngestResult jitter1 = temp(31);  // t=1000（同刻），边沿被挡
        clock.setFixed(1_500L);
        IngestResult low2 = temp(28);
        IngestResult jitter2 = temp(33);  // 边沿被挡
        IngestResult low3 = temp(27);
        IngestResult jitter3 = temp(32);  // 边沿被挡（基准不被刷新，窗口不会被抖动延长）

        log.info("[抖动] first.fired={} jitter1/2/3 suppressed={}/{}/{} suppressed 总数={}",
                fired(first),
                outcomeOf(jitter1).suppressedByCooldown(),
                outcomeOf(jitter2).suppressedByCooldown(),
                outcomeOf(jitter3).suppressedByCooldown(), suppressedCount());

        assertTrue(fired(first));
        assertEquals(1, firedCount(), "冷却窗口内抖动不得产生第二次触发");
        assertEquals(3, suppressedCount(), "三次冷却期穿越都应留下 COOLDOWN_ACTIVE 轨迹");
        RuleFireOutcome blocked = outcomeOf(jitter3);
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, blocked.skipReason());
        assertEquals(2_000L, blocked.retryAtMillis(), "窗口终点必须可预测: 1000+1000");
        assertEquals(0, blocked.records().size(), "被挡下时不得下发任何动作");
        // 动作只下发一次。
        assertEquals(1, gateway.commandCountForTest("plug", "on"),
                "冷却期内动作物理下发必须恰好一次");

        // t=1999 仍在窗口内：边沿依旧被挡。
        clock.setFixed(1_999L);
        IngestResult low4 = temp(29);
        IngestResult jitter4 = temp(34);
        assertFalse(fired(jitter4));

        // t=2000（窗口终点，左闭右开）：此时条件仍满足（非边沿），不触发——
        // 冷却不会把“本该等下一次边沿”的情况提前补发。
        clock.setFixed(2_000L);
        IngestResult stillHigh = temp(35);
        assertEquals(RuleSkipReason.STILL_SATISFIED, outcomeOf(stillHigh).skipReason(),
                "冷却刚结束但条件一直满足时，不能补发，必须等下一次边沿");
        assertEquals(1, firedCount());

        // 真正离开后，t=2500 再次进入：冷却已过，下一次边沿正常触发，不被漏发。
        clock.setFixed(2_500L);
        IngestResult leave = temp(20);
        IngestResult reEnter = temp(40);
        assertTrue(fired(reEnter), "冷却结束后的下一次 false→true 必须正常触发，不能被一直压着");
        assertEquals(2, firedCount());
    }

    @Test
    @DisplayName("冷却期内并发：一批乱序/重复/近乎同时的穿越事件涌入，窗口内绝不多次触发，窗口后边沿恰好再触发一次")
    void concurrentBurstDuringCooldown() throws Exception {
        ruleAdmin.setCooldown(RULE, 10_000L);
        // 先制造一次触发，t=1000 进入冷却，窗口到 11000。
        assertTrue(fired(temp(31)));
        temp(25); // 离开，制造后续边沿空间

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        AtomicLong localSeq = new AtomicLong(100);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long seq = localSeq.incrementAndGet();
                    // 一半事件让条件进入满足、一半离开，随机交错，全部落在冷却窗口内。
                    double value = (seq % 2 == 0) ? 31.0 : 29.0;
                    results.add(ingestService.ingest("temp-sensor", "temperature", value, seq,
                            "burst-" + seq, 50_000L + seq));
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

        log.info("[冷却并发] 涌入 {} 事件后 fired={}(期望1)", threads, firedCount());
        assertEquals(1, firedCount(), "冷却窗口内无论多少并发/交错边沿都不得多触发");
        // 物理副作用：plug 只在首次触发时下发过一次。
        assertEquals(1, gateway.commandCountForTest("plug", "on"));

        // 窗口结束，下一次边沿恰好触发一次（并发涌入可能让最后状态停在满足或不满足，先确定性离开再进入）。
        // 注意 seq 必须高于 burst 使用过的 101..124，否则会被序号单调性判为过期上报。
        seqGen.set(200);
        clock.setFixed(11_000L);
        temp(20);
        clock.setFixed(12_000L);
        IngestResult reEnter = temp(42);
        assertTrue(fired(reEnter), "冷却后的边沿不得被漏发");
        assertEquals(2, firedCount());
        assertEquals(2, gateway.commandCountForTest("plug", "on"), "窗口后重新触发必须真实下发第二次");
    }

    @Test
    @DisplayName("运行中调整冷却：调短立即放行、调长立即延长、置0立即关闭，且只影响后续事件")
    void runtimeCooldownAdjustmentTakesEffectImmediately() {
        Rule r = ruleAdmin.setCooldown(RULE, 10_000L);
        assertEquals(10_000L, r.cooldownMillis());
        // 首次触发 t=1000，窗口到 11000。
        assertTrue(fired(temp(31)));
        temp(20);
        clock.setFixed(5_000L);
        IngestResult blocked = temp(32);
        assertFalse(fired(blocked));
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, outcomeOf(blocked).skipReason());

        // 调短到 2000：基准仍是 1000，窗口终点变为 3000；t=5000 已在窗口外，下一次边沿立即放行。
        ruleAdmin.setCooldown(RULE, 2_000L);
        temp(20);
        clock.setFixed(5_000L);
        IngestResult allowed = temp(33);
        assertTrue(fired(allowed), "调短冷却后，窗口外的边沿必须立即放行");

        // 此刻 lastFiredAt=5000。调长到 100000：t=6000 的边沿必须被新窗口（到 105000）挡下。
        ruleAdmin.setCooldown(RULE, 100_000L);
        temp(20);
        clock.setFixed(6_000L);
        IngestResult blocked2 = temp(34);
        assertFalse(fired(blocked2), "调长冷却必须立即对后续边沿生效");
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, outcomeOf(blocked2).skipReason());

        // 置 0 关闭：紧接着的边沿立刻触发，行为恢复为纯边沿语义。
        ruleAdmin.setCooldown(RULE, 0L);
        temp(20);
        clock.setFixed(6_000L);
        IngestResult allowed2 = temp(35);
        assertTrue(fired(allowed2), "关闭冷却后边沿必须立即恢复触发");
        assertEquals(0L, ruleAdmin.get(RULE).cooldownMillis());
    }

    @Test
    @DisplayName("可区分性：事件判定与追溯中 COOLDOWN_ACTIVE 与 STILL_SATISFIED 明确不同，且冷却配置随规则可见")
    void cooldownSuppressionIsDistinguishableFromStillSatisfied() {
        ruleAdmin.setCooldown(RULE, 1_000L);
        // 首次触发 t=1000。
        IngestResult fired0 = temp(31);
        // 条件一直满足：非边沿 -> STILL_SATISFIED。
        IngestResult still = temp(32);
        // 离开再进入，t=1500 在窗口内 -> COOLDOWN_ACTIVE。
        clock.setFixed(1_500L);
        temp(20);
        IngestResult suppressed = temp(33);

        RuleFireOutcome stillO = outcomeOf(still);
        RuleFireOutcome suppressedO = outcomeOf(suppressed);
        log.info("[可区分] fired={} still.skip={} suppressed.skip={} decision(still)={} decision(suppressed)={}",
                fired(fired0), stillO.skipReason(), suppressedO.skipReason(),
                still.decision(), suppressed.decision());

        assertEquals(RuleSkipReason.STILL_SATISFIED, stillO.skipReason());
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, suppressedO.skipReason());
        assertNotNull(suppressedO.retryAtMillis());
        assertTrue(suppressed.decision().contains("COOLDOWN_ACTIVE"), "事件判定文本必须标明被冷却挡下");
        assertTrue(still.decision().contains("STILL_SATISFIED"), "事件判定文本必须标明持续满足");
        assertFalse(still.decision().contains("COOLDOWN_ACTIVE"));

        // 追溯轨迹里两类 skip 都各有一条，可分别统计。
        long stillTraces = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals(RULE)
                        && t.outcome().skipReason() == RuleSkipReason.STILL_SATISFIED).count();
        assertTrue(stillTraces >= 1);
        assertEquals(1, suppressedCount());

        // 规则视图暴露当前冷却配置与默认值。
        assertEquals(1_000L, ruleAdmin.get(RULE).cooldownMillis());
    }
}
