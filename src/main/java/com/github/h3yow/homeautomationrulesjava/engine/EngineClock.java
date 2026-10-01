package com.github.h3yow.homeautomationrulesjava.engine;

/**
 * 规则引擎的统一计时口径（冷却时间判定用）。
 *
 * 计时口径明确、可预测：所有“冷却是否已过”的判定只读取这一个时钟在
 * “边沿评估时刻”（事件被确认、进入规则串行评估的那一刻）给出的毫秒值，
 * 不使用事件自带的 eventTime，也不使用墙钟的其它采样点。
 *
 * 默认实现委托 {@link System#currentTimeMillis()}（单调推进的本地墙钟）；
 * 测试可通过 {@link #setFixedNow(long)} / {@link #advance(long)} 注入确定时间，
 * 从而无需 sleep 即可断言冷却边界。
 */
import org.springframework.stereotype.Component;

@Component
public class EngineClock {

    /** null 表示走真实墙钟；非 null 时返回固定值（测试注入）。 */
    private volatile Long fixedNow = null;

    public synchronized long now() {
        return fixedNow != null ? fixedNow : System.currentTimeMillis();
    }

    /** 测试支持：把时钟固定到某个绝对毫秒值。 */
    public synchronized void setFixedNow(long millis) {
        this.fixedNow = millis;
    }

    /** 测试支持：在当前固定时间上前推 millis 毫秒（必须先 {@link #setFixedNow(long)}）。 */
    public synchronized void advance(long millis) {
        if (fixedNow == null) {
            throw new IllegalStateException("advance requires setFixedNow first");
        }
        this.fixedNow = fixedNow + millis;
    }

    /** 测试支持：恢复为真实墙钟。 */
    public synchronized void useSystemClock() {
        this.fixedNow = null;
    }
}
