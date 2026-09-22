package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 规则管理门面：新增、替换（参数调整）、启停，均在运行中生效。 */
@Service
public class RuleService {

    private final RuleStore ruleStore;

    public RuleService(RuleStore ruleStore) {
        this.ruleStore = ruleStore;
    }

    public Rule add(Rule rule) {
        return ruleStore.add(rule);
    }

    public Rule replace(Rule rule) {
        return ruleStore.replace(rule);
    }

    public Rule setEnabled(String ruleId, boolean enabled) {
        return ruleStore.setEnabled(ruleId, enabled);
    }

    public Optional<Rule> get(String ruleId) {
        return ruleStore.get(ruleId);
    }

    public List<Rule> all() {
        return ruleStore.all();
    }
}
