package com.github.h3yow.homeautomationrulesjava.engine;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则引擎的统一计时口径。
 *
 * 冷却计时只认“引擎处理该已确认事件的墙钟时刻（毫秒）”，不使用事件自带的 eventTime：
 * 因为迟到/乱序事件的 eventTime 可能落在很久以前，用它计时会让结果取决于上报端时钟，
 * 无法预测。处理时刻是单调（对同一规则锁内而言）、由本地系统决定的，语义明确可复现。
 *
 * 默认实现读 System.currentTimeMillis()；测试可注入固定/可控时钟做确定性验证。
 */
@Component
public class RuleClock {

    /** 0 表示不使用固定时间，走系统墙钟。 */
    private final AtomicLong fixedNow = new AtomicLong(Long.MIN_VALUE);

    public long nowMillis() {
        long fixed = fixedNow.get();
        return fixed == Long.MIN_VALUE ? System.currentTimeMillis() : fixed;
    }

    /** 测试用：把时钟固定到某个毫秒时刻。 */
    public void setFixed(long millis) {
        fixedNow.set(millis);
    }

    /** 测试用：把固定时钟向前推进。 */
    public void advance(long deltaMillis) {
        fixedNow.updateAndGet(prev -> prev == Long.MIN_VALUE ? deltaMillis : prev + deltaMillis);
    }

    /** 测试用：恢复使用系统墙钟。 */
    public void resetToSystem() {
        fixedNow.set(Long.MIN_VALUE);
    }
}
