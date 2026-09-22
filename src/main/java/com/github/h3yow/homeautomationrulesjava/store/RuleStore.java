package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import java.util.List;
import java.util.Optional;

/** 规则存储：支持运行时新增、替换（参数调整）、启停。 */
public interface RuleStore {
    Rule add(Rule rule);

    Rule replace(Rule rule);

    Rule setEnabled(String ruleId, boolean enabled);

    Optional<Rule> get(String ruleId);

    List<Rule> enabled();

    List<Rule> all();
}
