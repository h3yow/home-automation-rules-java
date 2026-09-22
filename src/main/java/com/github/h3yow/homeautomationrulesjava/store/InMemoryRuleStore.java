package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.RuleException;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存规则存储。
 *
 * 运行时语义：
 *  - 每次新增/替换/启停都生成新版本的不可变 {@link RuleEntry}，并发读永远看到一致快照；
 *  - 引擎对一次触发持有单一 entry 快照，规则调整不会让同一次评估看到混合配置；
 *  - 每次成功变更版本号 +1，便于审计与参数调整追踪。
 */
@SuppressWarnings("unused")
@Component
public class InMemoryRuleStore implements RuleStore {

    private final ConcurrentMap<String, RuleEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Rule add(Rule rule) {
        validate(rule);
        RuleEntry created = new RuleEntry(rule, true, 1L);
        RuleEntry existing = entries.putIfAbsent(rule.id(), created);
        if (existing != null) {
            throw new RuleException("rule already exists: " + rule.id());
        }
        return rule;
    }

    @Override
    public Rule replace(Rule rule) {
        validate(rule);
        entries.compute(rule.id(), (id, old) -> {
            if (old == null) {
                throw new RuleException("rule not found: " + id);
            }
            return new RuleEntry(rule, old.enabled(), old.version() + 1);
        });
        return rule;
    }

    @Override
    public Rule setEnabled(String ruleId, boolean enabled) {
        RuleEntry updated = entries.computeIfPresent(ruleId, (id, old) -> {
            if (old.enabled() == enabled) {
                return old;
            }
            return new RuleEntry(old.rule(), enabled, old.version() + 1);
        });
        if (updated == null) {
            throw new RuleException("rule not found: " + ruleId);
        }
        return updated.rule();
    }

    /** 取条目快照（含启停状态与版本），供引擎使用。 */
    public Optional<RuleEntry> entry(String ruleId) {
        return Optional.ofNullable(entries.get(ruleId));
    }

    /** 当前所有启用规则的条目快照，按规则ID排序保证评估顺序确定。 */
    public List<RuleEntry> enabledEntries() {
        return entries.values().stream()
                .filter(RuleEntry::enabled)
                .sorted(java.util.Comparator.comparing(e -> e.rule().id()))
                .toList();
    }

    /** 全部条目快照。 */
    public List<RuleEntry> entries() {
        return entries.values().stream()
                .sorted(java.util.Comparator.comparing(e -> e.rule().id()))
                .toList();
    }

    @Override
    public Optional<Rule> get(String ruleId) {
        return Optional.ofNullable(entries.get(ruleId)).map(RuleEntry::rule);
    }

    @Override
    public List<Rule> enabled() {
        return enabledEntries().stream().map(RuleEntry::rule).toList();
    }

    @Override
    public List<Rule> all() {
        return entries().stream().map(RuleEntry::rule).toList();
    }

    /** 测试辅助。 */
    public void clear() {
        entries.clear();
    }

    private static void validate(Rule rule) {
        if (rule == null || rule.id() == null || rule.id().isBlank()) {
            throw new RuleException("rule id must not be blank");
        }
        if (rule.triggerDeviceId() == null || rule.triggerDeviceId().isBlank()) {
            throw new RuleException("triggerDeviceId must not be blank");
        }
        if (rule.conditions() == null || rule.actions() == null
                || rule.compensations() == null) {
            throw new RuleException("conditions/actions/compensations must not be null");
        }
    }
}
