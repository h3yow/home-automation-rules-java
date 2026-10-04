package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceCommandException;
import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.ConditionEvaluator;
import com.github.h3yow.homeautomationrulesjava.engine.EngineClock;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleEngine;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionExecutionRecord;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.model.Rule;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚状态一致性测试（动作序列中途失败 -> 补偿回滚链路）：
 *
 * 覆盖业务与断言口径——每个用例都同时断言四者一致：
 *   1) 动作实际下发次数（DeviceGateway.totalPhysicalWrites）；
 *   2) 回滚后查询到的设备已确认状态（StateStore.snapshotOf，即 /api/devices/{id}/state 的数据源）；
 *   3) 动作执行记录（RuleFireOutcome.records 与 ExecutionLog 轨迹中的 compensated 标记）；
 *   4) 最终结论（RuleFireOutcome.summary）。
 *
 * 用例：
 * - 中途失败回滚后，查询状态必须回到触发前原值（核心缺陷复现）；
 * - 触发前该属性本就没有已确认值：回滚后查询状态也不得有该属性，物理侧无残留；
 * - 补偿本身失败：状态保持动作后的值、记录不标 compensated、结论明确人工介入，绝不假装已恢复；
 * - 同一台设备被多个动作先后改动：回滚恢复的是触发前那一刻的原值；
 * - 回滚与“新状态被确认”竞争：回滚不得把其后已确认的新状态盖回旧值。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RollbackStateConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackStateConsistencyTest.class);

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
    @Autowired
    private RuleAdminService ruleAdmin;

    @BeforeEach
    void resetRuntimeState() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        // 只保留演示规则启用，本类其他用例创建的规则一律停用，避免互相触发干扰计数。
        for (Rule r : ruleStore.all()) {
            boolean shouldEnable = r.id().equals("rule-cool-down");
            if (r.enabled() != shouldEnable) {
                ruleStore.replace(r.withEnabled(shouldEnable));
            }
        }
    }

    /** 温度 25 -> 32 越过 30 阈值触发规则（eventId 前缀按用例区分，避免幂等去重串扰）。 */
    private void fireByTemperature(String eventIdPrefix) {
        ingestService.ingest("temp-sensor", "temperature", 25.0, 1L, eventIdPrefix + "-low", 100L);
        ingestService.ingest("temp-sensor", "temperature", 32.0, 2L, eventIdPrefix + "-high", 101L);
    }

    private RuleFireOutcome onlyFire() {
        assertEquals(1, executionLog.fireTraces().size(), "应只有一次规则触发记录");
        return executionLog.fireTraces().get(0).outcome();
    }

    private ActionExecutionRecord recordOf(RuleFireOutcome fire, String actionDefId) {
        return fire.records().stream()
                .filter(r -> r.action().actionDefId().equals(actionDefId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("中途失败回滚后：查询到的设备状态回到触发前原值，下发次数/状态/记录/结论四者一致")
    void rollbackRestoresQueriedStateToPreFireValue() {
        // 触发前原值：plug.on=true（物理侧与已确认状态都为 true）。
        gateway.sendCommand("bootstrap-plug", "plug", "on", true);
        stateStore.applyCommand("plug", "on", true, 90L);
        int writesBefore = gateway.totalPhysicalWrites();
        // 第二个动作 lock 永久超时 -> 重试耗尽 -> 触发对 plug 的补偿回滚。
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        fireByTemperature("rb-main");
        RuleFireOutcome fire = onlyFire();
        ActionExecutionRecord plugRec = recordOf(fire, "turn-off-plug");
        ActionExecutionRecord lockRec = recordOf(fire, "lock-door");

        Object queried = stateStore.snapshotOf("plug").valueOf("on");
        int writes = gateway.totalPhysicalWrites() - writesBefore;
        log.info("[回滚一致性-主复现] 查询状态 plug.on={}(期望true=触发前原值) 物理侧={} 动作下发次数={}(期望2: 动作1次+补偿1次)",
                queried, gateway.physicalValue("plug", "on"), writes);
        log.info("[回滚一致性-主复现] plug 记录 status={} compensated={} lock 记录 status={} reason={}",
                plugRec.status(), plugRec.compensated(), lockRec.status(), lockRec.failureReason());
        log.info("[回滚一致性-主复现] 最终结论:\n{}", fire.summary());

        // 1) 动作实际下发次数：plug 动作 1 次 + 补偿回滚 1 次（lock 重试 3 次均未产生物理副作用）。
        assertEquals(2, writes, "物理副作用 = 动作 1 次 + 补偿 1 次");
        // 2) 查询到的设备状态必须回到触发前原值 true（修复前此处为 false，与结论对不上）。
        assertEquals(Boolean.TRUE, queried, "回滚后查询状态必须等于触发前原值");
        assertEquals(Boolean.TRUE, gateway.physicalValue("plug", "on"), "物理侧同样回到原值");
        // 3) 动作执行记录：plug 已补偿、lock 失败；执行轨迹中的记录与结论里的记录一致。
        assertEquals(ActionStatus.SUCCESS, plugRec.status());
        assertTrue(plugRec.compensated(), "已成功的 plug 动作必须被标记为已补偿");
        assertEquals(ActionStatus.FAILED, lockRec.status());
        ActionExecutionRecord traced = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug"))
                .findFirst().orElseThrow();
        assertTrue(traced.compensated(), "执行轨迹中的记录必须同步回填为已补偿");
        // 4) 最终结论：明确已回滚到触发前状态。
        assertTrue(fire.partiallyFailed());
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());
        log.info("[回滚一致性-主复现] 通过：下发次数/查询状态/动作记录/最终结论四者一致");
    }

    @Test
    @DisplayName("触发前无该属性原值：回滚后查询状态也不含该属性，物理侧无残留，三者一致")
    void rollbackWhenPreFireValueMissing() {
        ruleAdmin.createRule("rule-rollback-missing", "回滚-触发前无原值", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(new Action("brighten", "light", "brightness", 55),
                        new Action("lock-fail", "lock", "locked", true)));
        ruleStore.replace(ruleStore.get("rule-cool-down").withEnabled(false));
        // 触发前 light.brightness 没有任何已确认值；lock 永久不可达使第二个动作失败。
        gateway.failPermanently("lock", "locked", ActionFailureReason.DEVICE_UNREACHABLE);

        fireByTemperature("rb-missing");
        RuleFireOutcome fire = onlyFire();
        ActionExecutionRecord brightenRec = recordOf(fire, "brighten");

        DeviceState lightState = stateStore.snapshotOf("light");
        int writes = gateway.totalPhysicalWrites();
        log.info("[回滚一致性-无原值] 查询状态 light.brightness 存在={} 物理侧={} 下发次数={}(期望2: 动作1次+清除1次)",
                lightState.get("brightness") != null, gateway.physicalValue("light", "brightness"), writes);
        log.info("[回滚一致性-无原值] 最终结论:\n{}", fire.summary());

        // 1) 下发次数：brighten 1 次 + 补偿清除 1 次；lock 未产生副作用。
        assertEquals(2, writes);
        // 2) 查询状态：触发前没有该属性，回滚后也不得有（不能残留动作值 55）。
        assertNull(lightState.get("brightness"), "触发前无原值时，回滚后查询状态不得含该属性");
        assertNull(gateway.physicalValue("light", "brightness"), "物理侧同样不得有残留");
        // 3) 记录：brighten 已补偿。
        assertTrue(brightenRec.compensated());
        // 4) 结论：已回滚到触发前状态（触发前即“无该属性”）。
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());
        log.info("[回滚一致性-无原值] 通过：状态/记录/结论三者一致（触发前无值 -> 回滚后无值）");
    }

    @Test
    @DisplayName("补偿本身失败：状态保持动作后的值、记录不标已补偿、结论明确人工介入，三者一致")
    void compensationFailureKeepsStateAndHonestConclusion() {
        // 本地确定性组装：补偿命令（comp: 前缀）必然失败的网关。
        DeviceRegistry registry = new DeviceRegistry();
        Map<String, PropertySpec> tempProps = new LinkedHashMap<>();
        tempProps.put("temperature", PropertySpec.decimal("temperature", -50.0, 100.0));
        registry.register(new Device("temp-sensor", "温度传感器", DeviceType.TEMPERATURE_SENSOR, tempProps));
        Map<String, PropertySpec> plugProps = new LinkedHashMap<>();
        plugProps.put("on", PropertySpec.bool("on"));
        registry.register(new Device("plug", "插座", DeviceType.PLUG, plugProps));
        Map<String, PropertySpec> lockProps = new LinkedHashMap<>();
        lockProps.put("locked", PropertySpec.bool("locked"));
        registry.register(new Device("lock", "门锁", DeviceType.LOCK, lockProps));

        StateStore localState = new StateStore();
        DeviceGateway compFailingGateway = new DeviceGateway() {
            @Override
            public void sendCommand(String idempotencyKey, String deviceId, String property, Object value) {
                if (idempotencyKey.startsWith("comp:")) {
                    throw new DeviceCommandException(ActionFailureReason.DEVICE_UNREACHABLE,
                            "compensation command rejected: " + idempotencyKey);
                }
                super.sendCommand(idempotencyKey, deviceId, property, value);
            }
        };
        compFailingGateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator evaluator = new ConditionEvaluator(localState, registry);
        ActionExecutor executor = new ActionExecutor(compFailingGateway, registry, localState, evaluator, localLog);
        RuleStore localRules = new RuleStore();
        RuleEngine engine = new RuleEngine(localRules, evaluator, executor, localLog, new EngineClock());
        localRules.add(new Rule("r-comp-fail", "补偿失败", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(new Action("turn-off-plug", "plug", "on", false),
                        new Action("lock-door", "lock", "locked", true)),
                1));

        // 触发前 plug.on=true；温度 25 -> 32 触发，plug 动作成功、lock 失败、补偿也失败。
        localState.applyCommand("plug", "on", true, 90L);
        localState.confirm("temp-sensor", "temperature", 25.0, 1L, 100L);
        engine.onStateConfirmed();
        localState.confirm("temp-sensor", "temperature", 32.0, 2L, 101L);
        List<RuleFireOutcome> outcomes = engine.onStateConfirmed();

        assertEquals(1, outcomes.size());
        RuleFireOutcome fire = outcomes.get(0);
        ActionExecutionRecord plugRec = recordOf(fire, "turn-off-plug");
        Object queried = localState.snapshotOf("plug").valueOf("on");
        log.info("[回滚一致性-补偿失败] 查询状态 plug.on={}(期望false=动作后的值，未恢复) compensated={}(期望false)",
                queried, plugRec.compensated());
        log.info("[回滚一致性-补偿失败] 最终结论:\n{}", fire.summary());

        // 状态保持动作后的值（未恢复），与“未补偿”的记录和结论严格一致。
        assertEquals(Boolean.FALSE, queried, "补偿失败时状态必须保持动作后的值，不得假装已恢复");
        assertFalse(plugRec.compensated(), "补偿失败不得标记 compensated");
        assertTrue(fire.summary().contains("SOME COMPENSATION FAILED"), fire.summary());
        assertFalse(fire.summary().contains("rolled back to pre-fire state"),
                "补偿失败时结论不得声称已回滚: " + fire.summary());
        // 补偿失败不占用幂等键：设备恢复后再次补偿可以真正执行。
        assertTrue(executor.compensate("r-comp-fail:1",
                new Action("turn-off-plug", "plug", "on", false),
                Map.of("plug", new DeviceState("plug", Map.of()))),
                "补偿失败后重试不应被幂等占位误判为已成功");
        log.info("[回滚一致性-补偿失败] 通过：状态=动作值、记录未补偿、结论提示人工介入，三者一致");
    }

    @Test
    @DisplayName("同一设备被多个动作先后改动：回滚恢复触发前那一刻的原值，而非中间值")
    void multipleActionsOnSameDeviceRollbackToOriginal() {
        ruleAdmin.createRule("rule-rollback-multi", "回滚-同设备多动作", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(new Action("dim-50", "light", "brightness", 50),
                        new Action("dim-80", "light", "brightness", 80),
                        new Action("lock-fail", "lock", "locked", true)));
        ruleStore.replace(ruleStore.get("rule-cool-down").withEnabled(false));
        // 触发前原值 brightness=10；lock 永久超时使第三个动作失败。
        stateStore.applyCommand("light", "brightness", 10, 90L);
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        fireByTemperature("rb-multi");
        RuleFireOutcome fire = onlyFire();
        ActionExecutionRecord rec50 = recordOf(fire, "dim-50");
        ActionExecutionRecord rec80 = recordOf(fire, "dim-80");

        Object queried = stateStore.snapshotOf("light").valueOf("brightness");
        int writes = gateway.totalPhysicalWrites();
        log.info("[回滚一致性-同设备多动作] 查询状态 brightness={}(期望10=触发前原值，非50/80) 下发次数={}(期望4: 动作2次+补偿2次)",
                queried, writes);
        log.info("[回滚一致性-同设备多动作] 最终结论:\n{}", fire.summary());

        // 1) 下发次数：两个动作各 1 次 + 两次补偿各 1 次。
        assertEquals(4, writes);
        // 2) 查询状态必须回到触发前那一刻的原值 10，而不是中间值 50 或 80。
        assertEquals(10, queried, "同设备多动作回滚必须恢复到触发前原值");
        assertEquals(10, gateway.physicalValue("light", "brightness"), "物理侧同样回到触发前原值");
        // 3) 两个动作记录都已补偿。
        assertTrue(rec50.compensated() && rec80.compensated(), "同一设备的两个动作都必须被补偿");
        // 4) 结论一致。
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());
        log.info("[回滚一致性-同设备多动作] 通过：触发前原值 10 在状态/记录/结论中一致");
    }

    @Test
    @DisplayName("回滚与新确认状态竞争：回滚不得把其后已确认的新状态盖回旧值")
    void rollbackDoesNotClobberNewerConfirmedState() {
        // 执行器级确定性构造：动作成功后、补偿前，该属性已有更新的值被确认。
        DeviceRegistry registry = new DeviceRegistry();
        Map<String, PropertySpec> lightProps = new LinkedHashMap<>();
        lightProps.put("brightness", PropertySpec.integer("brightness", 0L, 100L));
        registry.register(new Device("light", "灯", DeviceType.LIGHT, lightProps));
        StateStore localState = new StateStore();
        DeviceGateway localGateway = new DeviceGateway();
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator evaluator = new ConditionEvaluator(localState, registry);
        ActionExecutor executor = new ActionExecutor(localGateway, registry, localState, evaluator, localLog);

        // 触发前原值 10；动作把 brightness 改为 80。
        localState.applyCommand("light", "brightness", 10, 90L);
        DeviceState preSnap = localState.snapshotOf("light");
        Action brighten = new Action("brighten", "light", "brightness", 80);
        ActionExecutionRecord rec = executor.execute("r-race:1:brighten", brighten,
                List.of(), Map.of("light", preSnap));
        assertEquals(ActionStatus.SUCCESS, rec.status());

        // 竞争窗口：另一个控制源的新值 30 在回滚发生前已被确认。
        localState.applyCommand("light", "brightness", 30, 95L);

        boolean ok = executor.compensate("r-race:1", brighten, Map.of("light", preSnap));
        Object queried = localState.snapshotOf("light").valueOf("brightness");
        log.info("[回滚一致性-竞争] 补偿返回={} 查询状态 brightness={}(期望30=后确认的新值，不得被盖回10) 物理侧={}(期望10=已回滚)",
                ok, queried, localGateway.physicalValue("light", "brightness"));

        assertTrue(ok, "物理侧回滚成功，补偿整体视为成功");
        assertEquals(30, queried, "回滚不得覆盖动作之后已确认的新状态");
        assertEquals(10, localGateway.physicalValue("light", "brightness"), "物理侧已回滚到触发前原值");
        log.info("[回滚一致性-竞争] 通过：已确认的新状态不被回滚覆盖，终态为合法的新确认值");
    }
}
