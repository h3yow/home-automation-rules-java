package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.model.IngestOutcomeType;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
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

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：
 * 1) 同一物理事件（同 eventId）被多线程并发重复投递，只能被接受一次、动作序列只产生一次；
 * 2) 多个不同事件并发到达且都使条件处于满足态，边沿判定保证规则只触发一次。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentTriggerTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTriggerTest.class);

    @Autowired
    private EventIngestService ingestService;
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

    @BeforeEach
    void resetRuntimeState() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        if (!ruleStore.get("rule-cool-down").enabled()) {
            ruleStore.replace(ruleStore.get("rule-cool-down").withEnabled(true));
        }
    }

    @Test
    @DisplayName("同一事件并发重复投递：只接受一次，动作序列（2个动作）各只产生一次物理副作用")
    void sameEventConcurrentDeliveryFiresExactlyOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 完全相同的物理事件：同 eventId / 同 seq / 同值
                    IngestResult r = ingestService.ingest(
                            "temp-sensor", "temperature", 31.0, 1L,
                            "same-physical-event-001", 100L);
                    results.add(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在超时前完成");
        pool.shutdown();

        long accepted = results.stream().filter(r -> r.outcome() == IngestOutcomeType.ACCEPTED).count();
        long duplicated = results.stream().filter(r -> r.outcome() == IngestOutcomeType.IGNORED).count();

        long firedTraces = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down") && t.outcome().fired()).count();
        long plugCommands = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).count();
        long lockCommands = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("lock-door")).count();

        log.info("[并发判定] threads={} accepted={}(期望1) duplicated={} firedTraces={}(期望1) plug动作记录={}(期望1) lock动作记录={}(期望1)",
                threads, accepted, duplicated, firedTraces, plugCommands, lockCommands);
        log.info("[物理副作用断言] gateway plug.on={} lock.locked={}",
                gateway.physicalValue("plug", "on"), gateway.physicalValue("lock", "locked"));

        assertEquals(1, accepted, "同一事件并发重复投递只能有一个被接受");
        assertEquals(threads - 1, duplicated, "其余全部必须幂等判重");
        assertEquals(1, firedTraces, "规则只能边沿触发一次，不得重复触发");
        assertEquals(1, plugCommands, "动作序列不得重复下发：plug 一次");
        assertEquals(1, lockCommands, "动作序列不得重复下发：lock 一次");
        assertEquals(false, gateway.physicalValue("plug", "on"));
        assertEquals(true, gateway.physicalValue("lock", "locked"));
    }

    @Test
    @DisplayName("不同事件并发到达且条件同处满足态：边沿触发只允许一次，不漏发也不重复")
    void distinctConcurrentEventsFireOnce() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger seqGen = new AtomicInteger(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long seq = seqGen.getAndIncrement();
                    ingestService.ingest("temp-sensor", "temperature", 31.0 + (seq % 5),
                            seq, "distinct-event-" + seq, 100L + seq);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long firedTraces = executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals("rule-cool-down") && t.outcome().fired()).count();

        // 所有事件都使条件满足，边沿 false->true 全局只能发生一次，因此触发次数必须恰好为 1（不漏不重）。
        log.info("[并发判定] distinctEvents={} firedTraces={}(期望1，恰好一次)", threads, firedTraces);
        assertEquals(1, firedTraces, "条件组同一轮满足边沿只能触发一次：不漏发、不重复");

        // 且该次触发的动作序列完整（2 个动作都成功）。
        RuleFireOutcome outcome = executionLog.fireTraces().get(0).outcome();
        assertEquals(2, outcome.records().size());
        assertTrue(outcome.fullySuccessful(), outcome.summary());
    }
}
