package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ConditionEvaluator;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作失败链路测试：
 * - 瞬时不可达：重试后成功，物理副作用只有一次，attempts 可断言；
 * - 永久超时：重试耗尽，动作原因可区分 EXECUTION_TIMEOUT，
 *   序列中此前已成功动作被补偿回滚（部分失败可解释、无重复副作用）；
 * - 下发瞬间条件失效：动作 SKIPPED，原因 CONDITION_RECHECK_FAILED。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActionFailureTest {

    private static final Logger log = LoggerFactory.getLogger(ActionFailureTest.class);

    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private StateStore stateStore;
    @Autowired
    private RuleStore ruleStore;
    @Autowired
    private ExecutionLog executionLog;
    @Autowired
    private DeviceGateway gateway;
    @Autowired
    private ActionExecutor actionExecutor;

    @BeforeEach
    void resetRuntimeState() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        if (!ruleStore.get("rule-cool-down").enabled()) {
            ruleStore.replace(ruleStore.get("rule-cool-down").withEnabled(true));
        }
    }

    private void fireRule() {
        // 温度从 25 -> 32，越过 30 阈值触发规则。
        ingestService.ingest("temp-sensor", "temperature", 25.0, 1L,
                "fail-test-1", 100L);
        ingestService.ingest("temp-sensor", "temperature", 32.0, 2L,
                "fail-test-2", 101L);
    }

    @Test
    @DisplayName("瞬时不可达重试后成功：尝试次数可断言，物理副作用只发生一次")
    void transientUnreachableRetriedThenSucceeds() {
        // plug 前两次不可达、第三次成功。
        gateway.failTransiently("plug", "on", ActionFailureReason.DEVICE_UNREACHABLE, 2);

        fireRule();

        var plugRec = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();
        log.info("[失败链路] plug 瞬时不可达: status={} attempts={}(期望3) 原因={}",
                plugRec.status(), plugRec.attempts(), plugRec.failureReason());

        assertEquals(ActionStatus.SUCCESS, plugRec.status());
        assertEquals(3, plugRec.attempts(), "前两次失败后第三次成功");
        assertEquals(false, gateway.physicalValue("plug", "on"), "最终命令生效");

        // 整体成功，且每个动作只有一次物理副作用（成功后补偿不应发生）。
        RuleFireOutcome fire = executionLog.fireTraces().get(0).outcome();
        assertTrue(fire.fullySuccessful(), fire.summary());
        assertFalse(fire.partiallyFailed());
    }

    @Test
    @DisplayName("永久超时：原因可区分为 EXECUTION_TIMEOUT，重试耗尽后补偿已成功动作，整体部分失败可解释")
    void permanentTimeoutTriggersCompensationOfSucceededAction() {
        // 先建立设备触发前的已知状态（plug 原本是开着的），补偿必须把它回滚到 true。
        gateway.sendCommand("bootstrap-plug", "plug", "on", true);
        // 同时让状态存储拥有该“原值”，作为条件快照与补偿基线。
        stateStore.applyCommand("plug", "on", true, 90L);

        // 第一个动作 plug 正常成功（变为 false）；第二个动作 lock 永久超时 -> 重试耗尽。
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        fireRule();

        RuleFireOutcome fire = executionLog.fireTraces().get(0).outcome();
        var lockRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("lock-door")).findFirst().orElseThrow();
        var plugRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();

        log.info("[失败链路] lock 永久超时: status={} attempts={}(期望3) reason={}",
                lockRec.status(), lockRec.attempts(), lockRec.failureReason());
        log.info("[补偿链路] plug 补偿状态 compensated={}(期望true), 物理侧 plug.on={}(期望true=回滚到触发前原值)",
                plugRec.compensated(), gateway.physicalValue("plug", "on"));
        log.info("[整体结论]\n{}", fire.summary());

        // 失败原因可区分。
        assertEquals(ActionStatus.FAILED, lockRec.status());
        assertEquals(ActionFailureReason.EXECUTION_TIMEOUT, lockRec.failureReason());
        assertEquals(3, lockRec.attempts(), "重试上限为 3");

        // 此前成功动作被补偿（plug 回滚为触发前的 null，即没有任何残留副作用）。
        assertTrue(fire.partiallyFailed());
        assertTrue(plugRec.compensated(), "已成功的 plug 动作必须被补偿");
        // lock 从未成功，物理侧无残留；plug 已补偿回滚。
        assertEquals(Boolean.TRUE, gateway.physicalValue("plug", "on"),
                "补偿必须把 plug 回滚为触发前原值 true，且只回滚一次");
        assertEquals(null, gateway.physicalValue("lock", "locked"), "lock 从未成功，无残留副作用");
        // 补偿幂等：补偿键只执行一次。
        assertTrue(fire.summary().contains("rolled back to pre-fire state"));
    }

    @Test
    @DisplayName("设备永久不可达：原因为 DEVICE_UNREACHABLE，与超时可区分")
    void unreachableReasonDistinguishedFromTimeout() {
        gateway.failPermanently("plug", "on", ActionFailureReason.DEVICE_UNREACHABLE);

        fireRule();

        RuleFireOutcome fire = executionLog.fireTraces().get(0).outcome();
        var plugRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();

        log.info("[失败链路] plug 永久不可达: reason={}(期望DEVICE_UNREACHABLE) errors={}",
                plugRec.failureReason(), plugRec.errors());

        assertEquals(ActionFailureReason.DEVICE_UNREACHABLE, plugRec.failureReason());
        assertEquals(ActionStatus.FAILED, plugRec.status());
        // 第一个动作即失败，序列必须停止，lock 动作根本不允许下发。
        boolean lockEverExecuted = executionLog.actionRecords().stream()
                .anyMatch(r -> r.action().actionDefId().equals("lock-door"));
        assertFalse(lockEverExecuted, "前置动作失败后不得继续下发后续动作");
    }

    @Test
    @DisplayName("下发瞬间条件失效：动作被 SKIPPED 且原因 CONDITION_RECHECK_FAILED 可区分，且无物理副作用")
    void conditionRecheckFailureSkipsAction() {
        // 单元级确定性构造：不依赖时序竞态。
        // 1) 注册设备并让条件此刻“不成立”（温度 20 <= 30）。
        DeviceRegistry registry = new DeviceRegistry();
        Map<String, PropertySpec> tempProps = new LinkedHashMap<>();
        tempProps.put("temperature", PropertySpec.decimal("temperature", -50.0, 100.0));
        registry.register(new Device("temp-sensor", "温度传感器", DeviceType.TEMPERATURE_SENSOR, tempProps));
        Map<String, PropertySpec> plugProps = new LinkedHashMap<>();
        plugProps.put("on", PropertySpec.bool("on"));
        registry.register(new Device("plug", "插座", DeviceType.PLUG, plugProps));

        StateStore localState = new StateStore();
        localState.confirm("temp-sensor", "temperature", 20.0, 1L, 100L);
        DeviceGateway localGateway = new DeviceGateway();
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator localEvaluator = new ConditionEvaluator(localState, registry);
        ActionExecutor localExecutor = new ActionExecutor(
                localGateway, registry, localState, localEvaluator, localLog);

        // 2) 动作要求条件 temp>30，但下发瞬间真实状态只有 20 -> 复核失败，动作必须被跳过。
        Condition stillHot = new Condition("temp-sensor", "temperature", Operator.GT, 30);
        Action turnOff = new Action("turn-off-plug", "plug", "on", false);
        Map<String, com.github.h3yow.homeautomationrulesjava.model.DeviceState> pre =
                localEvaluator.snapshotsFor(List.of(stillHot));

        var rec = localExecutor.execute("rule-x:1:turn-off-plug", turnOff, List.of(stillHot), pre);

        log.info("[条件复核失败] status={}(期望SKIPPED) reason={}(期望CONDITION_RECHECK_FAILED) attempts={}(期望0)",
                rec.status(), rec.failureReason(), rec.attempts());
        log.info("[判定依据] {}", rec.errors());

        assertEquals(ActionStatus.SKIPPED, rec.status());
        assertEquals(ActionFailureReason.CONDITION_RECHECK_FAILED, rec.failureReason());
        assertEquals(0, rec.attempts(), "条件失效时不得真正下发，尝试次数为 0");
        assertEquals(null, localGateway.physicalValue("plug", "on"), "条件失效不得产生物理副作用");
    }
}
