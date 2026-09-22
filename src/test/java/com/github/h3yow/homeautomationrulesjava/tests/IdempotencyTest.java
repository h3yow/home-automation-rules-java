package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.ConditionEvaluator;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作幂等专项：同一 idempotencyKey 无论被调用多少次（成功后重复、失败后换调用方重试），
 * 物理副作用只允许一次，且重复调用直接复用首次记录。
 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private ActionExecutor newExecutor(DeviceGateway gateway, DeviceRegistry registry, StateStore state) {
        return new ActionExecutor(gateway, registry, state,
                new ConditionEvaluator(state, registry), new ExecutionLog(), 3, 1L);
    }

    private DeviceRegistry registryWithPlug() {
        DeviceRegistry registry = new DeviceRegistry();
        Map<String, PropertySpec> plugProps = new LinkedHashMap<>();
        plugProps.put("on", PropertySpec.bool("on"));
        registry.register(new Device("plug", "插座", DeviceType.PLUG, plugProps));
        return registry;
    }

    @Test
    @DisplayName("成功后的重复调用不再产生第二次物理副作用，直接复用首次记录")
    void repeatAfterSuccessIsIdempotent() {
        DeviceGateway gateway = new DeviceGateway();
        DeviceRegistry registry = registryWithPlug();
        StateStore state = new StateStore();
        ActionExecutor executor = newExecutor(gateway, registry, state);

        Action action = new Action("off", "plug", "on", false);
        Map<String, DeviceState> empty = Map.of();

        var first = executor.execute("k-1", action, List.of(), empty);
        var second = executor.execute("k-1", action, List.of(), empty);
        var third = executor.execute("k-1", action, List.of(), empty);

        log.info("[幂等] 三次调用 status: first={}, second={}, third={} attempts(first)={}",
                first.status(), second.status(), third.status(), first.attempts());

        assertEquals(ActionStatus.SUCCESS, first.status());
        assertTrue(second == first || second.attempts() == first.attempts(), "重复调用必须复用记录");
        assertEquals(1, gateway.executedCommandCount(), "物理副作用只允许一次");
        assertEquals(1, first.attempts());
    }

    @Test
    @DisplayName("失败重试耗尽后，同一键再次调用复用失败记录，不会出现重复副作用")
    void repeatAfterFailureIsIdempotentAndNoDuplicateSideEffect() {
        DeviceGateway gateway = new DeviceGateway();
        gateway.failPermanently("plug", "on", ActionFailureReason.EXECUTION_TIMEOUT);
        DeviceRegistry registry = registryWithPlug();
        StateStore state = new StateStore();
        ActionExecutor executor = newExecutor(gateway, registry, state);

        Action action = new Action("off", "plug", "on", false);

        var failed = executor.execute("k-2", action, List.of(), Map.of());
        var repeated = executor.execute("k-2", action, List.of(), Map.of());

        log.info("[幂等失败] failed status={} attempts={} reason={}; repeated attempts={}",
                failed.status(), failed.attempts(), failed.failureReason(), repeated.attempts());

        assertEquals(ActionStatus.FAILED, failed.status());
        assertEquals(ActionFailureReason.EXECUTION_TIMEOUT, failed.failureReason());
        assertEquals(3, failed.attempts(), "重试上限 3 次");
        // 失败已定论后，重复调用不应再尝试，避免任何潜在重复副作用。
        assertEquals(3, repeated.attempts(), "重复调用复用失败记录，不重新尝试");
        assertEquals(0, gateway.executedCommandCount(), "从未成功，物理副作用为 0");
    }

    @Test
    @DisplayName("瞬时故障：跨重试恢复成功，整体仅一次副作用，attempts 可断言")
    void transientFailureRecoversWithinRetries() {
        DeviceGateway gateway = new DeviceGateway();
        gateway.failTransiently("plug", "on", ActionFailureReason.DEVICE_UNREACHABLE, 2);
        DeviceRegistry registry = registryWithPlug();
        StateStore state = new StateStore();
        ActionExecutor executor = newExecutor(gateway, registry, state);

        var rec = executor.execute("k-3", new Action("off", "plug", "on", false),
                List.of(), Map.of());

        log.info("[瞬时恢复] status={} attempts={}(期望3) 最终物理值={}",
                rec.status(), rec.attempts(), gateway.physicalValue("plug", "on"));

        assertEquals(ActionStatus.SUCCESS, rec.status());
        assertEquals(3, rec.attempts());
        assertEquals(1, gateway.executedCommandCount(), "成功副作用恰好一次");
        assertEquals(false, gateway.physicalValue("plug", "on"));
    }
}
