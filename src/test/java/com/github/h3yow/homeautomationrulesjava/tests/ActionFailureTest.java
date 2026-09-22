package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.action.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.action.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.action.DefaultActionExecutor;
import com.github.h3yow.homeautomationrulesjava.action.FailureKind;
import com.github.h3yow.homeautomationrulesjava.action.SimulatedDeviceGateway;
import com.github.h3yow.homeautomationrulesjava.device.InMemoryDeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 动作失败原因区分、有界重试、无重复副作用、补偿与部分失败结果。 */
class ActionFailureTest {

    private static final Logger log = LoggerFactory.getLogger(ActionFailureTest.class);

    private InMemoryDeviceRegistry registry;
    private SimulatedDeviceGateway gateway;
    private DefaultActionExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDeviceRegistry();
        registry.register(new DeviceSpec("lamp-1", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("siren-1", DeviceType.SWITCH, 0d, 1d));
        // 补偿专用设备，便于把"补偿副作用"与"主动作副作用"分开计数
        registry.register(new DeviceSpec("lamp-1-rst", DeviceType.SWITCH, 0d, 1d));
        registry.register(new DeviceSpec("siren-1-rst", DeviceType.SWITCH, 0d, 1d));
        gateway = new SimulatedDeviceGateway(registry);
        executor = new DefaultActionExecutor(gateway);
    }

    private List<ActionDef> twoActions() {
        return List.of(new ActionDef("lamp-1", 1d, false),
                new ActionDef("siren-1", 1d, false));
    }

    private List<ActionDef> compensations() {
        return List.of(new ActionDef("lamp-1-rst", 0d, true),
                new ActionDef("siren-1-rst", 0d, true));
    }

    private void logReport(String title, ActionExecutor.ExecutionReport report) {
        log.info("{} => 整体结果={}；解释={}", title, report.outcome(), report.explanation());
        report.steps().forEach(s -> log.info("  动作#{} device={} status={} attempts={} failure={}",
                s.seq(), s.deviceId(), s.status(), s.attempts(), s.failureKind()));
        report.compensationSteps().forEach(s ->
                log.info("  补偿#{} device={} status={} attempts={} failure={}",
                        s.seq(), s.deviceId(), s.status(), s.attempts(), s.failureKind()));
    }

    @Test
    @DisplayName("瞬时故障（2次失败后恢复）在有界重试内成功，且只有一次副作用")
    void transientFailureRetriedOnceEffectively() {
        gateway.failNext("lamp-1", FailureKind.DEVICE_UNREACHABLE, 2);

        ActionExecutor.ExecutionReport report = executor.execute(
                "t-1", List.of(new ActionDef("lamp-1", 1d, false)),
                List.of(), 3, () -> true);
        logReport("瞬时不可达-注入2次失败", report);

        assertEquals(ActionExecutor.ExecutionOutcome.ALL_SUCCESS, report.outcome());
        assertEquals(ActionStatus.SUCCESS, report.steps().get(0).status());
        assertEquals(3, report.steps().get(0).attempts(), "首次+2次重试");
        assertEquals(1, gateway.executionCount("lamp-1"), "重试不得产生重复副作用");
        assertTrue(report.compensationSteps().isEmpty());
    }

    @Test
    @DisplayName("持续不可达：有界重试耗尽后失败，原因=DEVICE_UNREACHABLE，无副作用")
    void persistentUnreachableFailsDistinguishably() {
        gateway.setOffline("lamp-1", true);

        ActionExecutor.ExecutionReport report = executor.execute(
                "t-2", twoActions(), compensations(), 3, () -> true);
        logReport("设备持续离线", report);

        assertEquals(ActionExecutor.ExecutionOutcome.ALL_FAILED, report.outcome());
        assertEquals(ActionStatus.FAILED_FINAL, report.steps().get(0).status());
        assertEquals(FailureKind.DEVICE_UNREACHABLE, report.steps().get(0).failureKind());
        assertEquals(3, report.steps().get(0).attempts());
        assertEquals(0, gateway.executionCount("lamp-1"));
        assertEquals(0, gateway.executionCount("siren-1"), "序列在首个失败处停止");
        // 无任何动作成功 -> 没有需要回滚的副作用，不执行补偿
        assertEquals(0, report.compensationSteps().size());
    }

    @Test
    @DisplayName("慢设备超时：失败原因=TIMEOUT，与不可达可区分")
    void timeoutDistinguished() {
        gateway.setDelay("lamp-1", 500L); // 超过执行器 200ms 超时

        ActionExecutor.ExecutionReport report = executor.execute(
                "t-3", List.of(new ActionDef("lamp-1", 1d, false)),
                List.of(), 1, () -> true);
        logReport("慢设备超时", report);

        assertEquals(ActionStatus.FAILED_FINAL, report.steps().get(0).status());
        assertEquals(FailureKind.TIMEOUT, report.steps().get(0).failureKind());
    }

    @Test
    @DisplayName("部分失败：第1个动作成功、第2个失败 => PARTIAL_FAILURE，仅补偿已成功的设备")
    void partialFailureExplainedAndCompensated() {
        gateway.setOffline("siren-1", true);

        ActionExecutor.ExecutionReport report = executor.execute(
                "t-4", twoActions(), compensations(), 2, () -> true);
        logReport("部分失败-lamp成功siren不可达", report);

        assertEquals(ActionExecutor.ExecutionOutcome.PARTIAL_FAILURE, report.outcome());
        assertEquals(ActionStatus.SUCCESS, report.steps().get(0).status());
        assertEquals(ActionStatus.FAILED_FINAL, report.steps().get(1).status());
        assertEquals(FailureKind.DEVICE_UNREACHABLE, report.steps().get(1).failureKind());
        assertEquals(1, gateway.executionCount("lamp-1"));
        assertEquals(0, gateway.executionCount("siren-1"));
        // 仅对已成功的 lamp 执行一次补偿（siren 从未生效，不补偿）
        assertEquals(1, report.compensationSteps().size());
        assertEquals("lamp-1-rst", report.compensationSteps().get(0).deviceId());
        assertEquals(ActionStatus.COMPENSATED, report.compensationSteps().get(0).status());
        assertEquals(1, gateway.executionCount("lamp-1-rst"));
        assertEquals(0, gateway.executionCount("siren-1-rst"));
        assertNotNull(report.explanation());
        assertTrue(report.explanation().contains("DEVICE_UNREACHABLE"),
                "解释需包含可区分的失败原因");
    }

    @Test
    @DisplayName("执行前条件失效：动作被跳过，整体 NOT_EXECUTED，并执行补偿")
    void conditionInvalidBeforeActionSkipsSequence() {
        AtomicBoolean valid = new AtomicBoolean(false); // 执行动作前条件已不成立

        ActionExecutor.ExecutionReport report = executor.execute(
                "t-5", twoActions(), compensations(), 3, valid::get);
        logReport("条件失效", report);

        assertEquals(ActionExecutor.ExecutionOutcome.NOT_EXECUTED, report.outcome());
        assertEquals(ActionStatus.SKIPPED_CONDITION_INVALID, report.steps().get(0).status());
        assertEquals(FailureKind.CONDITION_INVALID, report.steps().get(0).failureKind());
        assertEquals(0, gateway.executionCount("lamp-1"), "条件失效不得执行任何动作");
        assertEquals(0, gateway.executionCount("siren-1"));
        assertEquals(0, report.compensationSteps().size(),
                "没有任何动作生效，无需补偿");
        assertTrue(report.explanation().contains("CONDITION_INVALID"));
    }

    @Test
    @DisplayName("相同 triggerId 的动作序列重放：幂等键保护，副作用不翻倍")
    void replaySameTriggerDoesNotDuplicateEffects() {
        List<ActionDef> actions = List.of(new ActionDef("lamp-1", 1d, false));
        executor.execute("t-6", actions, List.of(), 1, () -> true);
        executor.execute("t-6", actions, List.of(), 1, () -> true); // 同 triggerId 重放

        log.info("同 triggerId 重放后 lamp 实际副作用次数={}", gateway.executionCount("lamp-1"));
        assertEquals(1, gateway.executionCount("lamp-1"),
                "同一触发实例的动作即使重放也只生效一次");
    }
}
