package com.github.h3yow.homeautomationrulesjava.api;

import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 规则管理接口：新增、查询、运行中启停、运行中参数调整。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    public record ConditionDto(String deviceId, String property, String operator, Object expected) {
    }

    public record ActionDto(String actionDefId, String targetDeviceId, String property, Object value) {
    }

    public record RuleRequest(String id, String name, Boolean enabled,
                              List<ConditionDto> conditions, List<ActionDto> actions) {
    }

    public record EnableRequest(boolean enabled) {
    }

    private final RuleAdminService ruleAdmin;

    public RuleController(RuleAdminService ruleAdmin) {
        this.ruleAdmin = ruleAdmin;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody RuleRequest req) {
        Rule rule = ruleAdmin.createRule(req.id(), req.name(),
                req.enabled() == null || req.enabled(),
                toConditions(req.conditions()), toActions(req.actions()));
        return toView(rule);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return ruleAdmin.all().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toView(ruleAdmin.get(id));
    }

    @PatchMapping("/{id}/enabled")
    public Map<String, Object> setEnabled(@PathVariable String id, @RequestBody EnableRequest req) {
        return toView(ruleAdmin.setEnabled(id, req.enabled()));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody RuleRequest req) {
        return toView(ruleAdmin.updateDefinition(id, req.name(),
                toConditions(req.conditions()), toActions(req.actions())));
    }

    private List<Condition> toConditions(List<ConditionDto> dtos) {
        if (dtos == null) {
            throw new ApiException("INVALID_RULE", "conditions required");
        }
        return dtos.stream().map(d -> {
            try {
                return new Condition(d.deviceId(), d.property(), Operator.valueOf(d.operator()), d.expected());
            } catch (IllegalArgumentException e) {
                throw new ApiException("INVALID_OPERATOR",
                        "unknown operator: " + d.operator() + " (use EQ/NE/GT/GE/LT/LE)");
            }
        }).toList();
    }

    private List<Action> toActions(List<ActionDto> dtos) {
        if (dtos == null) {
            throw new ApiException("INVALID_RULE", "actions required");
        }
        return dtos.stream().map(d -> new Action(d.actionDefId(), d.targetDeviceId(), d.property(), d.value()))
                .toList();
    }

    private Map<String, Object> toView(Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("enabled", r.enabled());
        m.put("version", r.version());
        m.put("conditions", r.conditions());
        m.put("actions", r.actions());
        return m;
    }
}
