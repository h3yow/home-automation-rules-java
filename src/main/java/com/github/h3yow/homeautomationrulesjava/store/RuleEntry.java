package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.rule.Rule;

/**
 * 规则存储条目：配置 + 可变运行态（用不可变快照发布，支持并发读）。
 */
public record RuleEntry(Rule rule, boolean enabled, long version) {
}
