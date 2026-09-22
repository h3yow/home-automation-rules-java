package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则管理服务：新增、运行中启停、运行中参数调整。
 * 所有写操作先做引用合法性校验（条件/动作涉及的设备属性必须存在、值必须合法），
 * 再通过 RuleStore 的原子替换整体生效：在途执行读旧版本，新事件读新版本。
 */
@Service
public class RuleAdminService {

    private static final Logger log = LoggerFactory.getLogger(RuleAdminService.class);

    private final RuleStore ruleStore;
    private final DeviceRegistry deviceRegistry;

    public RuleAdminService(RuleStore ruleStore, DeviceRegistry deviceRegistry) {
        this.ruleStore = ruleStore;
        this.deviceRegistry = deviceRegistry;
    }

    /** 新增规则（默认 version=1），同时校验全部条件与动作。 */
    public Rule createRule(String id, String name, boolean enabled,
                           List<Condition> conditions, List<Action> actions) {
        validate(conditions, actions);
        Rule rule = new Rule(id, name, enabled, conditions, actions, 1);
        ruleStore.add(rule);
        log.info("规则已新增 id={} enabled={} conditions={} actions={}", id, enabled,
                conditions.size(), actions.size());
        return rule;
    }

    /** 运行中启停，立即对后续事件生效。 */
    public Rule setEnabled(String ruleId, boolean enabled) {
        Rule current = mustExist(ruleId);
        Rule updated = current.withEnabled(enabled);
        ruleStore.replace(updated);
        log.info("规则 {} 运行中{} (version={})", ruleId, enabled ? "启用" : "停用", updated.version());
        return updated;
    }

    /** 运行中参数调整（名称/条件/动作），立即对后续事件生效。 */
    public Rule updateDefinition(String ruleId, String name,
                                 List<Condition> conditions, List<Action> actions) {
        Rule current = mustExist(ruleId);
        validate(conditions, actions);
        Rule updated = current.withDefinition(name, conditions, actions);
        ruleStore.replace(updated);
        log.info("规则 {} 参数已运行中调整 (version={})", ruleId, updated.version());
        return updated;
    }

    public Rule get(String ruleId) {
        return mustExist(ruleId);
    }

    public List<Rule> all() {
        return ruleStore.all();
    }

    private Rule mustExist(String ruleId) {
        Rule current = ruleStore.get(ruleId);
        if (current == null) {
            throw new IllegalArgumentException("rule not found: " + ruleId);
        }
        return current;
    }

    private void validate(List<Condition> conditions, List<Action> actions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("rule must have at least one condition");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("rule must have at least one action");
        }
        for (Condition c : conditions) {
            Device d = deviceRegistry.find(c.deviceId());
            if (d == null) {
                throw new IllegalArgumentException("condition references unknown device: " + c.deviceId());
            }
            PropertySpec spec = d.specOf(c.property());
            if (spec == null) {
                throw new IllegalArgumentException("condition references unknown property: "
                        + c.deviceId() + "." + c.property());
            }
            spec.normalize(c.expected());
        }
        for (Action a : actions) {
            Device d = deviceRegistry.find(a.targetDeviceId());
            if (d == null) {
                throw new IllegalArgumentException("action targets unknown device: " + a.targetDeviceId());
            }
            PropertySpec spec = d.specOf(a.property());
            if (spec == null) {
                throw new IllegalArgumentException("action targets unknown property: "
                        + a.targetDeviceId() + "." + a.property());
            }
            spec.normalize(a.value());
        }
    }
}
