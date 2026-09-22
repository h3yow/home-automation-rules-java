package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.action.FailureKind;
import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.DeviceService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.engine.RuleService;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.model.RejectReason;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import com.github.h3yow.homeautomationrulesjava.store.AuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring 装配下端到端：注册 -> 上报 -> 触发 -> 失败重试 -> 补偿 -> 审计可追溯。 */
@SpringBootTest
class EndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndIntegrationTest.class);

    @Autowired private DeviceService deviceService;
    @Autowired private RuleService ruleService;
    @Autowired private SimulatedDeviceGateway gateway;
    @Autowired private InMemoryAuditLog auditLog;

    @Test
    @DisplayName("端到端：温度超阈值开风扇，风扇瞬时不可达后重试成功；全过程审计可追溯")
    void fullLifecycle() {
        // 使用测试专用ID，避免与演示数据/其它测试共享状态冲突
        deviceService.register(new DeviceSpec("e2e-temp", DeviceType.SENSOR, -40d, 80d));
        deviceService.register(new DeviceSpec("e2e-fan", DeviceType.SWITCH, 0d, 1d));
        deviceService.register(new DeviceSpec("e2e-fan-rst", DeviceType.SWITCH, 0d, 1d));

        ruleService.add(new Rule("e2e-rule", "高温开风扇(e2e)", "e2e-temp", TriggerOn.STATE_CHANGE,
                List.of(new Condition("e2e-temp", Operator.GT, 30d)),
                List.of(new ActionDef("e2e-fan", 1d, false)),
                List.of(new ActionDef("e2e-fan-rst", 0d, true))));

        // 正常事件不触发
        RuleEngine.EngineResult cool = deviceService.report(
                new EventReport("e2e-cool", "e2e-temp", 22d, 1000L));
        assertEquals(ReceiptStatus.ACCEPTED, cool.receipt().status());
        assertFalse(cool.triggers().get(0).matched());

        // 注入 1 次瞬时不可达，下一次高温事件应触发并重试成功
        gateway.failNext("e2e-fan", FailureKind.DEVICE_UNREACHABLE, 1);
        RuleEngine.EngineResult hot = deviceService.report(
                new EventReport("e2e-hot", "e2e-temp", 35d, 2000L));
        var report = hot.triggers().get(0).report();
        log.info("端到端触发结果: {}", report.explanation());
        assertTrue(hot.triggers().get(0).matched());
        assertEquals(1, gateway.executionCount("e2e-fan"), "重试后恰好一次副作用");
        assertTrue(report.steps().get(0).attempts() >= 2, "应发生过重试");

        // 乱序迟到事件被拒
        var stale = deviceService.report(
                new EventReport("e2e-old", "e2e-temp", 10d, 500L));
        assertEquals(ReceiptStatus.REJECTED, stale.receipt().status());
        assertEquals(RejectReason.STALE_EVENT, stale.receipt().reason());

        // 最终状态不回退
        DeviceState finalState = deviceService.state("e2e-temp").orElseThrow();
        assertEquals(35d, finalState.value());

        // 审计：本测试的三个事件、两次触发都有记录且含判定依据
        List<AuditLog.EventRecord> events = auditLog.events().stream()
                .filter(e -> e.event().eventId().startsWith("e2e-")).toList();
        List<AuditLog.TriggerRecord> triggers = auditLog.triggers().stream()
                .filter(t -> t.ruleId().equals("e2e-rule")).toList();
        log.info("端到端审计事件数={}, 触发记录数={}", events.size(), triggers.size());
        events.forEach(e -> log.info("审计[事件] #{} {} => {} ({})",
                e.seq(), e.event().eventId(), e.receipt().status(), e.basis()));
        triggers.forEach(t -> log.info("审计[触发] #{} rule={} matched={} ({})",
                t.seq(), t.ruleId(), t.matched(), t.basis()));
        assertEquals(3, events.size());
        assertEquals(2, triggers.size());
        assertTrue(triggers.stream().anyMatch(t -> t.basis().contains("GT 30.0")));
        assertTrue(triggers.stream().anyMatch(AuditLog.TriggerRecord::matched));
    }
}
