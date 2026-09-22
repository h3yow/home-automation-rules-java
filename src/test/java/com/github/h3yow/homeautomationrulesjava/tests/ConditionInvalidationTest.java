package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.action.DefaultActionExecutor;
import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryDeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryStateStore;
import com.github.h3yow.homeautomationrulesjava.engine.DefaultRuleEngine;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryRuleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件失效链路：规则有两个动作，第一个动作的"目标设备"同时也是条件观察设备时，
 * 首个动作执行后状态变化可能使第二个动作执行前的复查失败 —— 使用独立观察设备模拟
 * "触发与执行之间，外部更晚的事件已改变状态"。
 * 这里用更直接的构造：触发后立即注入更晚事件使条件翻转，再让第二个动作复查到失效。
 */
class ConditionInvalidationTest {

    private static final Logger log = LoggerFactory.getLogger(ConditionInvalidationTest.class);

    private InMemoryDeviceRegistry registry;
    private InMemoryStateStore stateStore;
    private InMemoryRuleStore ruleStore;
    private SimulatedDeviceGateway gateway;
    private DefaultRuleEngine engine;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDeviceRegistry();
        stateStore = new InMemoryStateStore(registry);
        ruleStore = new InMemoryRuleStore();
        gateway = new SimulatedDeviceGateway(registry);
        engine = new DefaultRuleEngine(stateStore, ruleStore,
                new DefaultActionExecutor(gateway), new InMemoryAuditLog(), 2);
        registry.register(new DeviceSpec("thermo", DeviceType.SENSOR, -40d, 80d));
        registry.register(new DeviceSpec("heater", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("fan", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("heater-rst", DeviceType.SWITCH, 0d, 1d));
        // 规则：温度<0 时先开加热器，再开风扇；补偿：关加热器
        ruleStore.add(new Rule("r-warm", "低温预热", "thermo", TriggerOn.STATE_CHANGE,
                List.of(new Condition("thermo", Operator.LT, 0d)),
                List.of(new ActionDef("heater", 1d, false),
                        new ActionDef("fan", 1d, false)),
                List.of(new ActionDef("heater-rst", 0d, true))));
    }

    @Test
    @DisplayName("正常低温：两个动作都执行")
    void coldRunsBothActions() {
        RuleEngine.EngineResult r = engine.handle(
                new EventReport("e-1", "thermo", -5d, 1000L));
        var report = r.triggers().get(0).report();
        log.info("低温触发: {}", report.explanation());
        assertEquals("ALL_SUCCESS", report.outcome().name());
        assertEquals(1, gateway.executionCount("heater"));
        assertEquals(1, gateway.executionCount("fan"));
    }

    @Test
    @DisplayName("初始即不满足条件：matched=false，完全不执行；重复事件也不会二次评估")
    void warmNoExecution() {
        EventReport event = new EventReport("e-1", "thermo", 25d, 1000L);
        RuleEngine.EngineResult r = engine.handle(event);
        log.info("常温触发 matched={} report={}", r.triggers().get(0).matched(),
                r.triggers().get(0).report());
        assertEquals(false, r.triggers().get(0).matched());
        assertEquals(0, gateway.executionCount("heater"));
        assertEquals(0, gateway.executionCount("fan"));

        // 未匹配的评估同样占用 (rule,event) 幂等键：重复事件不会再次评估
        RuleEngine.EngineResult again = engine.handle(event);
        assertTrue(again.triggers().isEmpty());
        assertEquals(0, gateway.executionCount("heater"));
    }

    @Test
    @DisplayName("动作执行期间条件被更晚事件翻转：第2个动作复查到 CONDITION_INVALID 而跳过并补偿已成功动作")
    void conditionFlipsDuringExecution() {
        // 确定性钩子：heater 动作即将"生效"时，先插入一条更晚的高温事件使 thermo<0 条件失效
        gateway.setBeforeExecuteHook("heater", () ->
                engine.handle(new EventReport("e-warm", "thermo", 25d, 2000L)));

        RuleEngine.EngineResult r = engine.handle(
                new EventReport("e-cold", "thermo", -5d, 1000L));

        var report = r.triggers().get(0).report();
        log.info("执行中条件翻转: {}", report.explanation());
        report.steps().forEach(st -> log.info("  动作#{} {} status={} failure={}",
                st.seq(), st.deviceId(), st.status(), st.failureKind()));
        report.compensationSteps().forEach(st -> log.info("  补偿#{} {} status={}",
                st.seq(), st.deviceId(), st.status()));

        assertEquals("PARTIAL_FAILURE", report.outcome().name());
        assertEquals("SUCCESS", report.steps().get(0).status().name());
        assertEquals("SKIPPED_CONDITION_INVALID", report.steps().get(1).status().name());
        assertEquals(com.github.h3yow.homeautomationrulesjava.action.FailureKind.CONDITION_INVALID,
                report.steps().get(1).failureKind());
        assertEquals(1, gateway.executionCount("heater"));
        assertEquals(0, gateway.executionCount("fan"), "条件失效后第二个动作不得下发");
        // 已成功的 heater 被补偿
        assertEquals(1, report.compensationSteps().size());
        assertEquals("heater-rst", report.compensationSteps().get(0).deviceId());
        assertTrue(report.explanation().contains("CONDITION_INVALID"));
    }
}
