package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.InMemoryDeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryStateStore;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.model.RejectReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设备状态上报：重复、乱序/迟到、非法取值、未注册设备、并发推进。 */
class StateReportingTest {

    private static final Logger log = LoggerFactory.getLogger(StateReportingTest.class);

    private InMemoryDeviceRegistry registry;
    private InMemoryStateStore store;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDeviceRegistry();
        store = new InMemoryStateStore(registry);
        registry.register(new DeviceSpec("temp-1", DeviceType.SENSOR, -40d, 80d));
    }

    private void logEvent(String title, EventReport e, EventReceipt r) {
        log.info("{} | 输入事件(eventId={},device={},value={},eventTime={}) => 受理={},原因={},version={}",
                title, e.eventId(), e.deviceId(), e.value(), e.eventTime(),
                r.status(), r.reason(), r.stateVersion());
    }

    @Test
    @DisplayName("重复上报幂等：同一 eventId 只确认一次，版本不重复推进")
    void duplicateEventIdempotent() {
        EventReport first = new EventReport("evt-1", "temp-1", 20.0, 1000L);
        EventReport duplicate = new EventReport("evt-1", "temp-1", 20.0, 1000L);

        EventReceipt r1 = store.accept(first);
        EventReceipt r2 = store.accept(duplicate);
        logEvent("首次上报", first, r1);
        logEvent("重复上报", duplicate, r2);

        assertEquals(ReceiptStatus.ACCEPTED, r1.status());
        assertEquals(1L, r1.stateVersion());
        assertEquals(ReceiptStatus.DUPLICATE, r2.status());
        assertEquals(1L, r2.stateVersion());
        assertEquals(20.0, store.current("temp-1").orElseThrow().value());
    }

    @Test
    @DisplayName("乱序与迟到事件被拒：已确认状态不回退")
    void outOfOrderAndLateRejected() {
        EventReport later = new EventReport("evt-arrives-first", "temp-1", 25.0, 2000L);
        EventReport earlier = new EventReport("evt-arrives-second", "temp-1", 10.0, 1000L);

        EventReceipt rLater = store.accept(later);
        EventReceipt rEarlier = store.accept(earlier);
        EventReport sameTime = new EventReport("evt-same-time", "temp-1", 9.0, 2000L);
        EventReceipt rSameTime = store.accept(sameTime);
        logEvent("先到事件(eventTime=2000)", later, rLater);
        logEvent("乱序事件(eventTime=1000)", earlier, rEarlier);
        logEvent("同时刻事件(eventTime=2000)", sameTime, rSameTime);

        assertEquals(ReceiptStatus.ACCEPTED, rLater.status());
        assertEquals(ReceiptStatus.REJECTED, rEarlier.status());
        assertEquals(RejectReason.STALE_EVENT, rEarlier.reason());
        assertEquals(ReceiptStatus.REJECTED, rSameTime.status());
        assertEquals(RejectReason.STALE_EVENT, rSameTime.reason());
        var state = store.current("temp-1").orElseThrow();
        assertEquals(25.0, state.value());
        assertEquals(1L, state.version());
    }

    @Test
    @DisplayName("未注册设备与非法取值被拒绝，原因可区分")
    void invalidInputRejectedWithDistinctReasons() {
        EventReport unknown = new EventReport("evt-x1", "ghost-device", 1.0, 1L);
        EventReport tooHigh = new EventReport("evt-x2", "temp-1", 99.0, 2L);
        EventReport nan = new EventReport("evt-x3", "temp-1", Double.NaN, 3L);

        EventReceipt rUnknown = store.accept(unknown);
        EventReceipt rTooHigh = store.accept(tooHigh);
        EventReceipt rNan = store.accept(nan);
        logEvent("未注册设备", unknown, rUnknown);
        logEvent("越界取值", tooHigh, rTooHigh);
        logEvent("NaN取值", nan, rNan);

        assertEquals(ReceiptStatus.REJECTED, rUnknown.status());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, rUnknown.reason());
        assertEquals(ReceiptStatus.REJECTED, rTooHigh.status());
        assertEquals(RejectReason.INVALID_VALUE, rTooHigh.reason());
        assertEquals(ReceiptStatus.REJECTED, rNan.status());
        assertEquals(RejectReason.INVALID_VALUE, rNan.reason());
        assertEquals(0, store.processedEventCount(), "被拒事件不得进入去重表");
    }

    @Test
    @DisplayName("并发上报被串行确认：版本单调连续无丢失，同事件并发重放只有一次生效")
    void concurrentReportsSerialised() throws Exception {
        int threads = 16;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        int total = threads * perThread;
        CountDownLatch done = new CountDownLatch(total);
        AtomicLong arrivalClock = new AtomicLong(1000L);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Long> versions = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 时间戳在真正"到达"时才领取，模拟实时产生、并发送达
                    EventReport e = new EventReport("evt-" + idx, "temp-1",
                            50.0, arrivalClock.getAndIncrement());
                    EventReceipt r = store.accept(e);
                    if (r.status() == ReceiptStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                        versions.add(r.stateVersion());
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        var state = store.current("temp-1").orElseThrow();
        log.info("并发上报完成: accepted={}, rejected(乱序)={}, 最终version={}, 最终value={}",
                accepted.get(), rejected.get(), state.version(), state.value());
        assertTrue(finished);
        assertEquals(threads * perThread, accepted.get() + rejected.get(),
                "每个事件要么被确认、要么因乱序被拒，无丢失无重复受理");
        assertEquals(accepted.get(), state.version(), "最终版本号等于被确认事件数");
        // 每个版本号恰好被发放一次：无重复、无跳号
        long distinct = versions.stream().distinct().count();
        assertEquals(accepted.get(), distinct, "版本号不得重复");
        assertEquals(accepted.get(), versions.size());
        long maxVersion = versions.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertEquals(accepted.get(), maxVersion);
    }
}
