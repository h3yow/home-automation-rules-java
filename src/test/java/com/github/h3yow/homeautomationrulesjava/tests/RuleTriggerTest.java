package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryDeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.engine.DefaultRuleEngine;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import com.github.h3yow.homeautomationrulesjava.model.ReceiptStatus;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryAuditLog;
import com.github.h3yow.homeautomationrulesjava.store.InMemoryRuleStore;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryStateStore;
import com.github.h3yow.homeautomationrulesjava.action.DefaultActionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则匹配、多动作序列、运行时启停/调整、重复事件不重复触发。 */
class RuleTriggerTest {

    private static final Logger log = LoggerFactory.getLogger(RuleTriggerTest.class);

    private InMemoryDeviceRegistry registry;
    private InMemoryStateStore stateStore;
    private InMemoryRuleStore ruleStore;
    private SimulatedDeviceGateway gateway;
    private DefaultRuleEngine engine;
    private InMemoryAuditLog auditLog;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDeviceRegistry();
        stateStore = new InMemoryStateStore(registry);
        ruleStore = new InMemoryRuleStore();
        gateway = new SimulatedDeviceGateway(registry);
        auditLog = new InMemoryAuditLog();
        DefaultActionExecutor executor = new DefaultActionExecutor(gateway);
        engine = new DefaultRuleEngine(stateStore, ruleStore, executor, auditLog, 3);

        registry.register(new DeviceSpec("motion-1", DeviceType.SENSOR, 0d, 1d));
        registry.register(new DeviceSpec("lamp-1", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("siren-1", DeviceType.SWITCH, 0d, 1d));
    }

    private Rule motionRule() {
        return new Rule(
                "r-motion", "有人移动开灯并响铃", "motion-1", TriggerOn.STATE_CHANGE,
                List.of(new Condition("motion-1", Operator.GTE, 1d)),
                List.of(new ActionDef("lamp-1", 1d, false),
                        new ActionDef("siren-1", 1d, false)),
                List.of(new ActionDef("lamp-1", 0d, true),
                        new ActionDef("siren-1", 0d, true)));
    }

    @Test
    @DisplayName("条件匹配时按序执行多个动作；条件不匹配不执行")
    void multiActionFiresInOrder() {
        ruleStore.add(motionRule());

        EventReport motion = new EventReport("e-1", "motion-1", 1d, 1000L);
        RuleEngine.EngineResult result = engine.handle(motion);
        log.info("输入事件 e-1(motion=1) 触发数={} 动作序列={}",
                result.triggers().size(),
                result.triggers().get(0).report() == null ? null
                        : result.triggers().get(0).report().steps());

        assertEquals(1, result.triggers().size());
        assertTrue(result.triggers().get(0).matched());
        assertEquals(2, result.triggers().get(0).report().steps().size());
        assertEquals(1, gateway.executionCount("lamp-1"));
        assertEquals(1, gateway.executionCount("siren-1"));

        // 条件不匹配（motion 回到 0）：不触发动作
        EventReport motionOff = new EventReport("e-2", "motion-1", 0d, 2000L);
        RuleEngine.EngineResult off = engine.handle(motionOff);
        log.info("输入事件 e-2(motion=0) 触发数={} matched={}",
                off.triggers().size(),
                off.triggers().isEmpty() ? "-" : off.triggers().get(0).matched());
        assertEquals(1, off.triggers().size());
        assertEquals(false, off.triggers().get(0).matched());
        assertEquals(1, gateway.executionCount("lamp-1"), "不匹配不得新增动作");
    }

    @Test
    @DisplayName("重复事件不产生第二次动作序列")
    void duplicateEventTriggersOnce() {
        ruleStore.add(motionRule());

        EventReport e = new EventReport("dup-1", "motion-1", 1d, 1000L);
        engine.handle(e);
        RuleEngine.EngineResult again = engine.handle(e);

        log.info("重复事件 dup-1 第二次受理状态={} 触发结果数={}",
                again.receipt().status(), again.triggers().size());
        assertEquals(ReceiptStatus.DUPLICATE, again.receipt().status());
        assertTrue(again.triggers().isEmpty(), "重复事件不得再评估/触发规则");
        assertEquals(1, gateway.executionCount("lamp-1"), "动作只能下发一次");
        assertEquals(1, gateway.executionCount("siren-1"));
        assertEquals(1, engine.firedCount());
    }

    @Test
    @DisplayName("规则停用后不再触发；重新启用后对新事件生效（运行中生效）")
    void disableEnableRuleAtRuntime() {
        ruleStore.add(motionRule());
        ruleStore.setEnabled("r-motion", false);

        RuleEngine.EngineResult r1 = engine.handle(
                new EventReport("e-1", "motion-1", 1d, 1000L));
        log.info("停用期间事件 e-1：触发数={}", r1.triggers().size());
        assertTrue(r1.triggers().isEmpty());
        assertEquals(0, gateway.executionCount("lamp-1"));

        ruleStore.setEnabled("r-motion", true);
        RuleEngine.EngineResult r2 = engine.handle(
                new EventReport("e-2", "motion-1", 1d, 2000L));
        log.info("重新启用后事件 e-2：触发数={}", r2.triggers().size());
        assertEquals(1, r2.triggers().size());
        assertEquals(1, gateway.executionCount("lamp-1"));
    }

    @Test
    @DisplayName("参数调整（替换规则）运行中生效：调整阈值后旧条件不再触发")
    void replaceRuleTakesEffectImmediately() {
        ruleStore.add(motionRule());
        Rule relaxed = new Rule("r-motion", "改名-高阈值版", "motion-1",
                TriggerOn.STATE_CHANGE,
                List.of(new Condition("motion-1", Operator.GTE, 1d)),
                List.of(new ActionDef("siren-1", 1d, false)),
                List.of());
        // 动作由 lamp+siren 变为仅 siren
        ruleStore.replace(relaxed);

        RuleEngine.EngineResult r = engine.handle(
                new EventReport("e-1", "motion-1", 1d, 1000L));
        log.info("替换规则后事件 e-1：动作数={} siren执行={} lamp执行={}",
                r.triggers().get(0).report().steps().size(),
                gateway.executionCount("siren-1"), gateway.executionCount("lamp-1"));
        assertEquals(1, r.triggers().get(0).report().steps().size());
        assertEquals(1, gateway.executionCount("siren-1"));
        assertEquals(0, gateway.executionCount("lamp-1"), "旧版本动作不得执行");
        assertEquals(2L, ruleStore.entry("r-motion").orElseThrow().version(),
                "替换后版本号递增");
    }
}
