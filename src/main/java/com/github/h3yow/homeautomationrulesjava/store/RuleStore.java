package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.Rule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则存储（本地内存模拟）。
 *
 * - rules 为不可变快照 + volatile 引用：新增/启停/参数调整通过原子替换整体生效，
 *   在途读取拿到的永远是某一个一致版本；
 * - satisfied 记录每条规则条件组“上一次评估是否成立”，供引擎做边沿触发，
 *   避免条件持续满足时重复触发；
 * - fireCount 为单调触发编号，保证每次真正触发获得唯一递增序号；
 * - lastFiredAt 记录每条规则“上一次真正触发（动作序列开始执行）”的处理时刻，
 *   供冷却判定使用；只在真正触发时更新，被冷却挡下的边沿不刷新它，
 *   因此冷却窗口不会被抖动边沿无限顺延。
 */
@Component
public class RuleStore {

    private record Snapshot(Map<String, Rule> rules) {
    }

    private volatile Snapshot snapshot = new Snapshot(Map.of());
    private final Map<String, Boolean> satisfied = new ConcurrentHashMap<>();
    private final Map<String, Long> fireCount = new ConcurrentHashMap<>();
    /** ruleId -> 上一次真正触发的处理时刻（RuleClock 毫秒）；0 表示从未触发。 */
    private final Map<String, Long> lastFiredAt = new ConcurrentHashMap<>();

    /** 新增规则。 */
    public synchronized void add(Rule rule) {
        Map<String, Rule> next = new ConcurrentHashMap<>(snapshot.rules());
        if (next.putIfAbsent(rule.id(), rule) != null) {
            throw new IllegalStateException("rule already exists: " + rule.id());
        }
        snapshot = new Snapshot(Map.copyOf(next));
        satisfied.putIfAbsent(rule.id(), Boolean.FALSE);
        fireCount.putIfAbsent(rule.id(), 0L);
        lastFiredAt.putIfAbsent(rule.id(), 0L);
    }

    /** 整体原子替换规则定义（启停或参数调整后调用）。 */
    public synchronized void replace(Rule rule) {
        Map<String, Rule> next = new ConcurrentHashMap<>(snapshot.rules());
        if (!next.containsKey(rule.id())) {
            throw new IllegalArgumentException("rule not found: " + rule.id());
        }
        next.put(rule.id(), rule);
        snapshot = new Snapshot(Map.copyOf(next));
        satisfied.putIfAbsent(rule.id(), Boolean.FALSE);
        fireCount.putIfAbsent(rule.id(), 0L);
        lastFiredAt.putIfAbsent(rule.id(), 0L);
    }

    public Rule get(String ruleId) {
        return snapshot.rules().get(ruleId);
    }

    public List<Rule> all() {
        return new ArrayList<>(snapshot.rules().values());
    }

    /** 读取并原子更新条件组边沿状态：仅 false -> true 返回 true。 */
    public boolean transitionToSatisfied(String ruleId, boolean nowSatisfied) {
        Boolean prev = satisfied.getOrDefault(ruleId, Boolean.FALSE);
        satisfied.put(ruleId, nowSatisfied);
        return nowSatisfied && !prev;
    }

    /** 读取当前边沿状态（不更新）。 */
    public boolean isSatisfied(String ruleId) {
        return satisfied.getOrDefault(ruleId, Boolean.FALSE);
    }

    /** 原子领取下一个触发编号；并发调用只会有一个线程拿到每个编号。 */
    public long nextFireNo(final String ruleId) {
        return fireCount.merge(ruleId, 1L, Long::sum);
    }

    /** 读取上一次真正触发的处理时刻；从未触发返回 0。调用方需持有该规则锁。 */
    public long lastFiredAt(final String ruleId) {
        return lastFiredAt.getOrDefault(ruleId, 0L);
    }

    /**
     * 仅在规则真正触发（边沿通过冷却检查、动作序列开始执行）时记录处理时刻。
     * 被冷却挡下的边沿不得调用本方法，否则窗口会被抖动无限顺延。
     * 调用方需持有该规则锁。
     */
    public void markFiredAt(final String ruleId, final long atMillis) {
        lastFiredAt.put(ruleId, atMillis);
    }

    /** 测试/演示重置：清空边沿状态、触发计数与冷却计时基准，保留规则定义本身。 */
    public void resetRuntime() {
        satisfied.replaceAll((k, v) -> Boolean.FALSE);
        fireCount.replaceAll((k, v) -> 0L);
        lastFiredAt.replaceAll((k, v) -> 0L);
    }
}
