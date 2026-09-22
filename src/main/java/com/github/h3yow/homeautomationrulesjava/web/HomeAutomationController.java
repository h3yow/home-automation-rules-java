package com.github.h3yow.homeautomationrulesjava.web;

import com.github.h3yow.homeautomationrulesjava.action.FailureKind;
import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.device.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.engine.DeviceService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.engine.RuleService;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.RuleException;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import com.github.h3yow.homeautomationrulesjava.store.AuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 本地 HTTP 接口（仅用于本地验证与演示）。 */
@SuppressWarnings("unused")
@RestController
@RequestMapping("/api")
public class HomeAutomationController {

    private final DeviceService deviceService;
    private final RuleService ruleService;
    private final DeviceRegistry deviceRegistry;
    private final SimulatedDeviceGateway gateway;
    private final InMemoryAuditLog auditLog;

    public HomeAutomationController(DeviceService deviceService, RuleService ruleService,
                                    DeviceRegistry deviceRegistry,
                                    SimulatedDeviceGateway gateway, InMemoryAuditLog auditLog) {
        this.deviceService = deviceService;
        this.ruleService = ruleService;
        this.deviceRegistry = deviceRegistry;
        this.gateway = gateway;
        this.auditLog = auditLog;
    }

    // ---- 设备 ----

    public record DeviceDto(String deviceId, DeviceType type, Double minValue, Double maxValue) {
    }

    @PostMapping("/devices")
    public DeviceSpec registerDevice(@RequestBody DeviceDto dto) {
        double min = dto.minValue() == null ? 0d : dto.minValue();
        double max = dto.maxValue() == null ? 1d : dto.maxValue();
        DeviceSpec spec = new DeviceSpec(dto.deviceId(), dto.type(), min, max);
        deviceService.register(spec);
        return spec;
    }

    @GetMapping("/devices")
    public List<DeviceSpec> devices() {
        return deviceRegistry.all();
    }

    @GetMapping("/states")
    public List<DeviceState> states() {
        return deviceService.states();
    }

    @GetMapping("/states/{deviceId}")
    public ResponseEntity<DeviceState> state(@PathVariable String deviceId) {
        return deviceService.state(deviceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- 事件上报 ----

    public record EventDto(String eventId, String deviceId, Double value, Long eventTime) {
    }

    @PostMapping("/events")
    public RuleEngine.EngineResult reportEvent(@RequestBody EventDto dto) {
        long time = dto.eventTime() == null ? System.currentTimeMillis() : dto.eventTime();
        return deviceService.report(new EventReport(
                dto.eventId(), dto.deviceId(), dto.value(), time));
    }

    // ---- 规则 ----

    public record RuleDto(String id, String name, String triggerDeviceId, TriggerOn triggerOn,
                          List<Condition> conditions, List<ActionDef> actions,
                          List<ActionDef> compensations) {
    }

    @PostMapping("/rules")
    public Rule addRule(@RequestBody RuleDto dto) {
        return ruleService.add(toRule(dto));
    }

    @PostMapping("/rules/{id}")
    public Rule replaceRule(@PathVariable String id, @RequestBody RuleDto dto) {
        return ruleService.replace(toRule(dto));
    }

    @PostMapping("/rules/{id}/enabled")
    public Rule setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        return ruleService.setEnabled(id, enabled);
    }

    @GetMapping("/rules")
    public List<Rule> rules() {
        return ruleService.all();
    }

    private static Rule toRule(RuleDto dto) {
        TriggerOn on = dto.triggerOn() == null ? TriggerOn.STATE_CHANGE : dto.triggerOn();
        return new Rule(dto.id(), dto.name(), dto.triggerDeviceId(), on,
                dto.conditions() == null ? List.of() : dto.conditions(),
                dto.actions() == null ? List.of() : dto.actions(),
                dto.compensations() == null ? List.of() : dto.compensations());
    }

    // ---- 审计 ----

    @GetMapping("/audit/events")
    public List<AuditLog.EventRecord> eventAudit() {
        return auditLog.events();
    }

    @GetMapping("/audit/triggers")
    public List<AuditLog.TriggerRecord> triggerAudit() {
        return auditLog.triggers();
    }

    // ---- 测试/演示用故障注入 ----

    @PostMapping("/sim/fail-next")
    public Map<String, Object> failNext(@RequestParam String deviceId,
                                        @RequestParam FailureKind kind,
                                        @RequestParam(defaultValue = "1") int times) {
        gateway.failNext(deviceId, kind, times);
        return Map.of("deviceId", deviceId, "kind", kind.name(), "times", times);
    }

    @PostMapping("/sim/offline")
    public Map<String, Object> offline(@RequestParam String deviceId,
                                       @RequestParam boolean offline) {
        gateway.setOffline(deviceId, offline);
        return Map.of("deviceId", deviceId, "offline", offline);
    }

    @GetMapping("/sim/executions")
    public Map<String, Integer> executions() {
        return Map.of("totalSideEffects", gateway.totalExecutionCount());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "INVALID_ARGUMENT", "message", e.getMessage()));
    }

    @ExceptionHandler(RuleException.class)
    public ResponseEntity<Map<String, String>> handleRule(RuleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "RULE_ERROR", "message", e.getMessage()));
    }
}
