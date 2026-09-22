package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.action.DefaultActionExecutor;
import com.github.h3yow.homeautomationrulesjava.action.FailureKind;
import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryDeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryStateStore;
import com.github.h3yow.homeautomationrulesjava.engine.DefaultRuleEngine;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryRuleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发触发幂等：同一事件并发 N 次，动作序列只执行一次、无漏发。 */
class ConcurrentTriggerTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTriggerTest.class);

    private InMemoryDeviceRegistry registry;
    private InMemoryStateStore stateStore;
    private InMemoryRuleStore ruleStore;
    private SimulatedDeviceGateway gateway;
    private DefaultRuleEngine engine;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDeviceRegistry();
        stateStore = new InMemoryStateStore(registry);
        ruleStore = new InMemoryRuleStore();
        gateway = new SimulatedDeviceGateway(registry);
        InMemoryAuditLog auditLog = new InMemoryAuditLog();
        engine = new DefaultRuleEngine(stateStore, ruleStore,
                new DefaultActionExecutor(gateway), auditLog, 2);

        registry.register(new DeviceSpec("motion-1", DeviceType.SENSOR, 0d, 1d));
        registry.register(new DeviceSpec("lamp-1", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("siren-1", DeviceType.SWITCH, 0d, 1d));
        ruleStore.add(new Rule("r1", "并发测试规则", "motion-1", TriggerOn.STATE_CHANGE,
                List.of(new Condition("motion-1", Operator.GTE, 1d)),
                List.of(new ActionDef("lamp-1", 1d, false),
                        new ActionDef("siren-1", 1d, false)),
                List.of()));
    }

    @Test
    @DisplayName("同一事件并发上报：状态只确认一次，动作序列只执行一次")
    void sameEventConcurrentFiresOnce() throws Exception {
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger triggerReports = new AtomicInteger();

        EventReport event = new EventReport("same-event", "motion-1", 1d, 1000L);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RuleEngine.EngineResult r = engine.handle(event);
                    if (r.receipt().status() == ReceiptStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else if (r.receipt().status() == ReceiptStatus.DUPLICATE) {
                        duplicate.incrementAndGet();
                    }
                    triggerReports.addAndGet(r.triggers().size());
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

        log.info("并发数={} accepted={} duplicate={} 携带触发结果的线程数={} lamp副作用={} siren副作用={}",
                n, accepted.get(), duplicate.get(), triggerReports.get(),
                gateway.executionCount("lamp-1"), gateway.executionCount("siren-1"));

        assertEquals(1, accepted.get(), "同一事件只能被确认一次");
        assertEquals(n - 1, duplicate.get(), "其余全部为重复");
        assertEquals(1, engine.firedCount(), "(rule,event) 组合只触发一次");
        assertEquals(1, gateway.executionCount("lamp-1"), "动作不得重复下发");
        assertEquals(1, gateway.executionCount("siren-1"));
        assertEquals(1, triggerReports.get(),
                "只有确认事件的那个线程携带触发结果，重复事件不触发，动作无漏发");
    }

    @Test
    @DisplayName("不同事件并发：每个被确认事件各触发一次，动作不丢不重；乱序事件不触发")
    void distinctEventsConcurrentAllFired() throws Exception {
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final long t = 1000L + i;
            EventReport event = new EventReport("evt-" + i, "motion-1", 1d, t);
            pool.submit(() -> {
                try {
                    start.await();
                    RuleEngine.EngineResult r = engine.handle(event);
                    if (r.receipt().status() == ReceiptStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else {
                        stale.incrementAndGet(); // 并发乱序 -> STALE_EVENT
                    }
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

        log.info("不同事件并发: 事件数={} 确认={} 乱序拒绝={} 触发数={} lamp副作用={}",
                n, accepted.get(), stale.get(), engine.firedCount(),
                gateway.executionCount("lamp-1"));
        assertEquals(n, accepted.get() + stale.get(), "无丢失");
        assertEquals(accepted.get(), engine.firedCount(),
                "每个被确认事件恰好触发一次");
        assertEquals(accepted.get(), gateway.executionCount("lamp-1"),
                "动作下发次数与触发次数一致，不丢不重");
    }

    @Test
    @DisplayName("并发触发遇瞬时故障：只有一个线程执行重试，副作用仍恰好一次")
    void concurrentTriggerWithTransientFailure() throws Exception {
        gateway.failNext("lamp-1", FailureKind.DEVICE_UNREACHABLE, 1);

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        EventReport event = new EventReport("flaky-event", "motion-1", 1d, 5000L);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    engine.handle(event);
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

        log.info("并发+瞬时故障: lamp副作用={} siren副作用={}",
                gateway.executionCount("lamp-1"), gateway.executionCount("siren-1"));
        assertEquals(1, gateway.executionCount("lamp-1"));
        assertEquals(1, gateway.executionCount("siren-1"));
    }
}
