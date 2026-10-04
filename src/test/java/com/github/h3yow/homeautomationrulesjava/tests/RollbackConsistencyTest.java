package com.github.h3yow.homeautomationrulesjava.tests;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.ActionExecutor;
import com.github.h3yow.homeautomationrulesjava.engine.ConditionEvaluator;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.ActionStatus;
import com.github.h3yow.homeautomationrulesjava.model.CompensationStatus;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.model.RuleFireOutcome;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import com.github.h3yow.homeautomationrulesjava.store.RuleStore;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败回滚一致性专项测试。
 *
 * <p>覆盖业务：动作序列中途失败触发回滚后，①动作实际下发次数 ②查到的设备状态（StateStore）
 * ③动作执行记录（compensationStatus/compensated） ④最终结论（summary）四者严格一致；
 * 并覆盖边界：触发前原值缺失（恢复为“无值”）、回滚失败（不得声称已恢复）、
 * 同一台设备被多个动作先后改动（只回滚一次、回到触发前原值）、
 * 回滚与新上报几乎同时发生（回滚不得覆盖已确认的新状态）、拿不到触发前快照。</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RollbackConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackConsistencyTest.class);

    @Autowired private EventIngestService ingestService;
    @Autowired private StateStore stateStore;
    @Autowired private RuleStore ruleStore;
    @Autowired private RuleAdminService ruleAdmin;
    @Autowired private ExecutionLog executionLog;
    @Autowired private DeviceGateway gateway;
    @Autowired private ActionExecutor actionExecutor;

    private final AtomicLong idSeq = new AtomicLong(5000);

    private String eid() {
        return "rb-test-event-" + idSeq.incrementAndGet();
    }

    @BeforeEach
    void resetRuntimeState() {
        stateStore.reset();
        ruleStore.resetRuntime();
        ingestService.clearProcessedEventIds();
        executionLog.clear();
        gateway.reset();
        actionExecutor.reset();
        // Spring 上下文跨用例共享：停用本类其它用例创建的临时规则，保证每个用例触发时
        // 只有“演示规则 + 本用例显式启用的规则”会执行，命令计数互不串扰。
        for (String id : List.of("rb-absent", "rb-multi", "rb-race", "rb-no-cd")) {
            if (ruleStore.get(id) != null && ruleStore.get(id).enabled()) {
                ruleAdmin.setEnabled(id, false);
            }
        }
        if (ruleStore.get("rule-cool-down") != null
                && !ruleStore.get("rule-cool-down").enabled()) {
            ruleAdmin.setEnabled("rule-cool-down", true);
        }
    }

    /**
     * 用例结束后停用本类创建的全部临时规则。Spring 上下文跨测试类共享，
     * 若不停用，残留的启用规则会让后续测试类的一次上报触发额外规则、污染命令/轨迹计数。
     */
    @AfterEach
    void disableTempRules() {
        for (String id : List.of("rb-absent", "rb-multi", "rb-race", "rb-no-cd")) {
            if (ruleStore.get(id) != null && ruleStore.get(id).enabled()) {
                ruleAdmin.setEnabled(id, false);
            }
        }
    }

    /** 让 temp-sensor.temperature 从 25 -> 32 越过 30 阈值，触发所有以该条件触发的启用规则。 */
    private void triggerByTemperature() {
        ingestService.ingest("temp-sensor", "temperature", 25.0, 100L, eid(), 100L);
        ingestService.ingest("temp-sensor", "temperature", 32.0, 101L, eid(), 101L);
    }

    private RuleFireOutcome latestFiredOutcome(String ruleId) {
        return executionLog.fireTraces().stream()
                .filter(t -> t.ruleId().equals(ruleId) && t.outcome().fired())
                .reduce((a, b) -> b).orElseThrow().outcome();
    }

    private void logBanner(String title) {
        log.info("==================== 回滚一致性业务：{} ====================", title);
    }

    private void registerLightBrightness(DeviceRegistry registry) {
        Map<String, PropertySpec> props = new LinkedHashMap<>();
        props.put("brightness", PropertySpec.integer("brightness", 0L, 100L));
        registry.register(new Device("light", "灯", DeviceType.LIGHT, props));
    }

    // ------------------------------------------------------------------
    // 用例 1：中途失败回滚后，下发次数 / 设备状态 / 动作记录 / 最终结论四者一致
    // ------------------------------------------------------------------
    @Test
    @DisplayName("中途失败回滚后：下发次数/查询状态/动作记录/最终结论四者一致，状态恢复为触发前原值")
    void midSequenceFailureRollsBackAndFourSourcesAgree() {
        logBanner("序列中途失败-四者一致");

        // 触发前：查询侧与物理侧 plug.on 都为原值 true。
        gateway.sendCommand("bootstrap-plug", "plug", "on", true);
        stateStore.applyCommand("plug", "on", true, 90L);
        // 第二个动作 lock 永久超时 -> 重试耗尽。
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        int commandsBefore = gateway.successfulCommandCount();
        triggerByTemperature();

        RuleFireOutcome fire = latestFiredOutcome("rule-cool-down");
        var plugRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();
        var lockRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("lock-door")).findFirst().orElseThrow();
        var plugLogRec = executionLog.actionRecords().stream()
                .filter(r -> r.action().actionDefId().equals("turn-off-plug")).findFirst().orElseThrow();

        // ① 实际下发次数：plug 动作 1 次 + plug 回滚 1 次；lock 3 次尝试全部失败（无成功副作用）。
        int deltaCommands = gateway.successfulCommandCount() - commandsBefore;
        // ② 查到的设备状态。
        Object queriedPlug = stateStore.snapshotOf("plug").valueOf("on");
        // ③ 物理侧。
        Object physicalPlug = gateway.physicalValue("plug", "on");

        log.info("[四者一致] fire.summary=\n{}", fire.summary());
        log.info("[四者一致] 成功命令增量={}(期望2: plug下发1+回滚1) plug.attempts={} lock.attempts={}",
                deltaCommands, plugRec.attempts(), lockRec.attempts());
        log.info("[四者一致] 查询侧 plug.on={}(期望true=触发前原值) 物理侧 plug.on={}", queriedPlug, physicalPlug);
        log.info("[四者一致] plug 记录 status={} compensationStatus={} compensated={}",
                plugRec.status(), plugRec.compensationStatus(), plugRec.compensated());

        // ① 下发次数。
        assertEquals(2, deltaCommands, "plug 成功下发 1 次 + 回滚下发 1 次；失败的 lock 无成功副作用");
        assertEquals(3, lockRec.attempts(), "lock 重试耗尽 3 次");
        assertNull(gateway.physicalValue("lock", "locked"), "lock 从未成功，物理侧无残留");

        // ② 查询侧状态必须恢复为触发前原值。
        assertEquals(Boolean.TRUE, queriedPlug, "回滚完成后查到的设备状态必须与触发前原值一致");
        assertEquals(Boolean.TRUE, physicalPlug, "物理侧也已回到触发前原值");

        // ③ 动作执行记录（结果对象与追溯轨迹同源一致）。
        assertEquals(ActionStatus.SUCCESS, plugRec.status());
        assertEquals(CompensationStatus.RESTORED, plugRec.compensationStatus());
        assertTrue(plugRec.compensated());
        assertEquals(CompensationStatus.RESTORED, plugLogRec.compensationStatus(),
                "追溯轨迹中的同一条记录也必须标记为 RESTORED");
        assertEquals(CompensationStatus.NOT_NEEDED, lockRec.compensationStatus(),
                "失败动作本身不补偿");

        // ④ 最终结论必须明确“已回滚到触发前”，且与状态/记录一致。
        assertTrue(fire.partiallyFailed());
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());
    }

    // ------------------------------------------------------------------
    // 用例 2：触发前原值缺失（该属性没有值），回滚后查询侧该属性必须不存在
    // ------------------------------------------------------------------
    @Test
    @DisplayName("触发前原值缺失：回滚后查询侧/物理侧该属性都恢复为无值，记录与结论仍为已恢复")
    void absentPreFireValueRollsBackToAbsent() {
        logBanner("触发前原值缺失-恢复为无值");

        // light.brightness 触发前查询侧与物理侧都没有值；独立规则：brightness=80 后 lock 失败。
        ruleAdmin.setEnabled("rule-cool-down", false);
        ruleAdmin.createRule("rb-absent", "原值缺失回滚", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(
                        new Action("set-brightness", "light", "brightness", 80L),
                        new Action("lock-door", "lock", "locked", true)),
                null);
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        triggerByTemperature();

        RuleFireOutcome fire = latestFiredOutcome("rb-absent");
        var brightRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("set-brightness")).findFirst().orElseThrow();

        Object queriedBrightness = stateStore.snapshotOf("light").valueOf("brightness");
        Object physicalBrightness = gateway.physicalValue("light", "brightness");
        boolean propertyPresentInQuery = stateStore.snapshotOf("light").get("brightness") != null;

        log.info("[原值缺失] 查询侧 light.brightness={} present={}（期望缺失）; 物理侧={}",
                queriedBrightness, propertyPresentInQuery, physicalBrightness);
        log.info("[原值缺失] 记录 compensationStatus={} compensated={}",
                brightRec.compensationStatus(), brightRec.compensated());
        log.info("[原值缺失] summary=\n{}", fire.summary());

        assertNull(queriedBrightness, "触发前没有该属性值，回滚后查询侧必须恢复为无值");
        assertFalse(propertyPresentInQuery, "查询侧快照中不应再出现 brightness 属性");
        assertNull(physicalBrightness, "物理侧同样被清除，恢复到触发前无值状态");
        assertEquals(CompensationStatus.RESTORED, brightRec.compensationStatus());
        assertTrue(brightRec.compensated());
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());
    }

    // ------------------------------------------------------------------
    // 用例 3：回滚命令失败：查询侧仍是被改成的值，记录 FAILED，结论绝不能说已恢复
    // （确定性构造：先成功执行动作，再对回滚目标注入永久故障，然后直接驱动补偿）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("回滚失败：查询侧仍是被改成的值，记录为 FAILED，绝不允许声称已回滚")
    void rollbackFailureKeepsStateAndSummaryHonest() {
        logBanner("回滚失败-状态与结论一致地不恢复");

        DeviceRegistry registry = new DeviceRegistry();
        registerLightBrightness(registry);
        StateStore localState = new StateStore();
        DeviceGateway localGateway = new DeviceGateway();
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator localEval = new ConditionEvaluator(localState, registry);
        ActionExecutor localExecutor = new ActionExecutor(localGateway, registry, localState, localEval, localLog);

        // 触发前原值 40，保存“触发前快照”（CAS 基线）。
        localState.applyCommand("light", "brightness", 40L, 10L);
        Map<String, DeviceState> preSnapshots = Map.of("light", localState.snapshotOf("light"));

        // 动作成功下发到 80（此时无故障）。
        Action set80 = new Action("set-brightness", "light", "brightness", 80L);
        var successRec = localExecutor.execute("rb-fail:1:set-brightness", set80, List.of(), Map.of());
        assertEquals(ActionStatus.SUCCESS, successRec.status());
        assertEquals(80L, localState.snapshotOf("light").valueOf("brightness"));

        // 动作成功之后注入永久故障：随后回滚命令必然失败（回滚键 comp:... 与动作键不同）。
        localGateway.failPermanently("light", "brightness", ActionFailureReason.DEVICE_UNREACHABLE);

        CompensationStatus status = localExecutor.compensate("rb-fail:1", set80, preSnapshots);
        var marked = localExecutor.markCompensation(successRec, status);

        log.info("[回滚失败] compensationStatus={}(期望FAILED) 查询侧仍为={}(期望80，未恢复) 物理侧={}",
                status, localState.snapshotOf("light").valueOf("brightness"),
                localGateway.physicalValue("light", "brightness"));

        assertEquals(CompensationStatus.FAILED, status, "物理回滚失败必须明确返回 FAILED");
        assertFalse(marked.compensated(), "FAILED 不允许表现为已补偿/已恢复");
        assertEquals(80L, localState.snapshotOf("light").valueOf("brightness"),
                "回滚失败时查询侧状态必须停留在被改成的 80，不能假装恢复成 40");
        assertEquals(80L, localGateway.physicalValue("light", "brightness"),
                "物理侧回滚命令失败，仍是 80");
    }

    // ------------------------------------------------------------------
    // 用例 4：同一设备被多个动作先后改动，回滚一次直接恢复为触发前原值
    // ------------------------------------------------------------------
    @Test
    @DisplayName("同设备多动作先后改动：只回滚一次并恢复为触发前原值，两条记录均为 RESTORED，次数一致")
    void multipleActionsSameDeviceRollsBackOnceToPreFireValue() {
        logBanner("同设备多动作-去重回滚一次");

        // 触发前 brightness=40（查询侧与物理侧一致）。
        gateway.sendCommand("bootstrap-light", "light", "brightness", 40L);
        stateStore.applyCommand("light", "brightness", 40L, 90L);

        ruleAdmin.setEnabled("rule-cool-down", false);
        ruleAdmin.createRule("rb-multi", "同设备多动作回滚", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(
                        new Action("set-brightness-80", "light", "brightness", 80L),
                        new Action("set-brightness-20", "light", "brightness", 20L),
                        new Action("lock-door", "lock", "locked", true)),
                null);
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        int commandsBefore = gateway.successfulCommandCount();
        triggerByTemperature();

        RuleFireOutcome fire = latestFiredOutcome("rb-multi");
        var recs = fire.records();
        var b80 = recs.stream().filter(r -> r.action().actionDefId().equals("set-brightness-80"))
                .findFirst().orElseThrow();
        var b20 = recs.stream().filter(r -> r.action().actionDefId().equals("set-brightness-20"))
                .findFirst().orElseThrow();
        var lockRec = recs.stream().filter(r -> r.action().actionDefId().equals("lock-door"))
                .findFirst().orElseThrow();

        // 成功命令增量：brightness 两次下发 + brightness 回滚一次（bootstrap 在计数基准前已扣除）。
        int delta = gateway.successfulCommandCount() - commandsBefore;
        Object queried = stateStore.snapshotOf("light").valueOf("brightness");
        Object physical = gateway.physicalValue("light", "brightness");

        log.info("[同设备多动作] 成功命令增量={}(期望3: 80、20各1 + 回滚1) 查询侧brightness={} 物理侧={}",
                delta, queried, physical);
        log.info("[同设备多动作] b80={} b20={} lock.attempts={}",
                b80.compensationStatus(), b20.compensationStatus(), lockRec.attempts());
        log.info("[同设备多动作] summary=\n{}", fire.summary());

        assertEquals(3, delta, "两个动作各下发一次 + 该属性只回滚一次");
        assertEquals(40L, ((Number) queried).longValue(), "查询侧必须恢复为触发前原值 40");
        assertEquals(40L, ((Number) physical).longValue(), "物理侧也必须回到 40");
        assertEquals(CompensationStatus.RESTORED, b80.compensationStatus());
        assertEquals(CompensationStatus.RESTORED, b20.compensationStatus());
        assertTrue(b80.compensated() && b20.compensated());
        assertEquals(ActionStatus.FAILED, lockRec.status());
        assertTrue(fire.summary().contains("rolled back to pre-fire state"), fire.summary());

        // 追溯轨迹中 brightness 两条记录都必须是 RESTORED，且只有一次回滚物理命令（由增量=3 保证）。
        long restoredBrightness = executionLog.actionRecords().stream()
                .filter(r -> r.action().targetDeviceId().equals("light"))
                .filter(r -> r.compensationStatus() == CompensationStatus.RESTORED).count();
        assertEquals(2, restoredBrightness, "两条 brightness 动作记录都标记为 RESTORED");
    }

    // ------------------------------------------------------------------
    // 用例 5：触发后、回滚前有新的设备上报被确认 -> 回滚必须让位，状态保留新值，不产生回滚命令
    // ------------------------------------------------------------------
    @Test
    @DisplayName("回滚与新上报竞争：触发后确认的新上报胜出，回滚不覆盖、不下发，状态/记录一致")
    void newerConfirmedReportWinsOverRollback() {
        logBanner("回滚与新上报竞争-新上报不被覆盖");

        DeviceRegistry registry = new DeviceRegistry();
        registerLightBrightness(registry);
        StateStore localState = new StateStore();
        DeviceGateway localGateway = new DeviceGateway();
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator localEval = new ConditionEvaluator(localState, registry);
        ActionExecutor localExecutor = new ActionExecutor(localGateway, registry, localState, localEval, localLog);

        // 触发前原值 40，并保留触发前快照（CAS 基线）。
        localState.applyCommand("light", "brightness", 40L, 10L);
        Map<String, DeviceState> preSnapshots = Map.of("light", localState.snapshotOf("light"));

        // 动作成功下发到 80。
        Action set80 = new Action("set-brightness", "light", "brightness", 80L);
        var successRec = localExecutor.execute("rb-race:1:set-brightness", set80, List.of(), Map.of());
        assertEquals(ActionStatus.SUCCESS, successRec.status());

        // 在动作成功之后、回滚之前，设备实际上报了一个新值 60（高 seq），必须能被确认：
        // 动作回写不占用上报序号，动作之后的真实上报不能被当作乱序丢弃。
        var confirm = localState.confirm("light", "brightness", 60L, 200L, 11L);
        assertEquals(StateStore.ConfirmStatus.ACCEPTED, confirm.status(),
                "动作之后设备产生的真实上报必须能被确认，不能被当作乱序丢弃");

        // 序列后续失败，触发回滚：识别到触发后已有新上报，整体跳过（物理侧也不下发）。
        CompensationStatus status = localExecutor.compensate("rb-race:1", set80, preSnapshots);
        var marked = localExecutor.markCompensation(successRec, status);

        Object queried = localState.snapshotOf("light").valueOf("brightness");
        log.info("[回滚让位] status={}(期望SUPERSEDED) compensated={}(期望false) 查询侧={}(期望60) 成功命令数={}(期望1，无回滚命令)",
                status, marked.compensated(), queried, localGateway.successfulCommandCount());

        assertEquals(CompensationStatus.SUPERSEDED_BY_NEW_REPORT, status);
        assertFalse(marked.compensated(), "让位新上报不算已回滚，compensated 必须为 false");
        assertEquals(60L, ((Number) queried).longValue(), "查询侧必须保留后来确认的新值 60");
        assertEquals(80L, ((Number) localGateway.physicalValue("light", "brightness")).longValue(),
                "回滚不下发任何物理命令，物理侧停留在动作值（新上报尚未驱动物理回写的真实模拟）");
        assertEquals(1, localGateway.successfulCommandCount(), "回滚被跳过，不得再产生回滚命令");

        // 幂等：再次补偿结论一致。
        assertEquals(CompensationStatus.SUPERSEDED_BY_NEW_REPORT,
                localExecutor.compensate("rb-race:1", set80, preSnapshots));
    }

    // ------------------------------------------------------------------
    // 用例 6：拿不到触发前原值（触发前快照中没有目标设备）-> ORIGINAL_UNAVAILABLE
    // ------------------------------------------------------------------
    @Test
    @DisplayName("拿不到触发前快照：补偿结果为 ORIGINAL_UNAVAILABLE，状态不被改动，记录如实呈现")
    void originalValueUnavailableIsReportedHonestly() {
        logBanner("触发前原值不可得-如实上报");

        DeviceRegistry registry = new DeviceRegistry();
        Map<String, PropertySpec> plugProps = new LinkedHashMap<>();
        plugProps.put("on", PropertySpec.bool("on"));
        registry.register(new Device("plug", "插座", DeviceType.PLUG, plugProps));

        StateStore localState = new StateStore();
        DeviceGateway localGateway = new DeviceGateway();
        ExecutionLog localLog = new ExecutionLog();
        ConditionEvaluator localEval = new ConditionEvaluator(localState, registry);
        ActionExecutor localExecutor = new ActionExecutor(localGateway, registry, localState, localEval, localLog);

        Action turnOff = new Action("turn-off-plug", "plug", "on", false);
        var successRec = localExecutor.execute("rb-noorig:1:turn-off-plug", turnOff, List.of(), Map.of());
        assertEquals(ActionStatus.SUCCESS, successRec.status());

        // 触发前快照中根本没有目标设备（模拟拿不到触发前原值）。
        CompensationStatus status = localExecutor.compensate("rb-noorig:1", turnOff, Map.of());
        var marked = localExecutor.markCompensation(successRec, status);

        log.info("[原值不可得] status={}(期望ORIGINAL_UNAVAILABLE) compensated={}(期望false) 查询侧plug.on={}",
                status, marked.compensated(), localState.snapshotOf("plug").valueOf("on"));

        assertEquals(CompensationStatus.ORIGINAL_UNAVAILABLE, status);
        assertFalse(marked.compensated(), "拿不到原值时绝不能表现为已恢复");
        assertEquals(Boolean.FALSE, localState.snapshotOf("plug").valueOf("on"),
                "查询侧保持动作改成的值不变（系统无法安全回滚，等待人工介入）");
        assertEquals(Boolean.FALSE, localGateway.physicalValue("plug", "on"),
                "物理侧同样保持动作值，未下发任何回滚命令");
        // 幂等：再次补偿结论一致，不会二次下发。
        assertEquals(CompensationStatus.ORIGINAL_UNAVAILABLE,
                localExecutor.compensate("rb-noorig:1", turnOff, Map.of()));
        assertEquals(1, localGateway.successfulCommandCount(), "只有动作那一次成功命令，无回滚命令");
    }

    // ------------------------------------------------------------------
    // 用例 7：未配置冷却的规则，回滚行为与既有行为一致（冷却与回滚相互正交）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("无冷却规则：失败回滚同样恢复为触发前原值，四者一致，行为与既有规则一致")
    void rollbackWorksTheSameWithoutCooldown() {
        logBanner("无冷却规则-回滚行为不变");

        ruleAdmin.setEnabled("rule-cool-down", false);
        ruleAdmin.createRule("rb-no-cd", "无冷却回滚", true,
                List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                List.of(
                        new Action("set-brightness", "light", "brightness", 70L),
                        new Action("lock-door", "lock", "locked", true)),
                null);
        assertNull(ruleAdmin.get("rb-no-cd").cooldownMillis(), "该规则未配置冷却");

        gateway.sendCommand("bootstrap-light", "light", "brightness", 50L);
        stateStore.applyCommand("light", "brightness", 50L, 90L);
        gateway.failPermanently("lock", "locked", ActionFailureReason.EXECUTION_TIMEOUT);

        triggerByTemperature();

        RuleFireOutcome fire = latestFiredOutcome("rb-no-cd");
        var brightRec = fire.records().stream()
                .filter(r -> r.action().actionDefId().equals("set-brightness")).findFirst().orElseThrow();

        Object queried = stateStore.snapshotOf("light").valueOf("brightness");
        Object physical = gateway.physicalValue("light", "brightness");
        log.info("[无冷却] 查询侧brightness={}(期望50) 物理侧={} compensationStatus={}",
                queried, physical, brightRec.compensationStatus());

        assertEquals(50L, ((Number) queried).longValue());
        assertEquals(50L, ((Number) physical).longValue());
        assertEquals(CompensationStatus.RESTORED, brightRec.compensationStatus());
        assertTrue(brightRec.compensated());
        assertTrue(fire.summary().contains("rolled back to pre-fire state"));
    }

    // ------------------------------------------------------------------
    // 用例 8：上报与回滚真正多线程并发压测——任何一次结果都必须满足
    // “最终查到的状态要么是触发前原值 40，要么是被确认的新上报值 60”，
    // 且补偿状态与查询状态严格对应，绝不出现状态/结论错位。
    // ------------------------------------------------------------------
    @Test
    @DisplayName("上报与回滚并发：200 轮竞速下最终状态只能是原值或新确认值，补偿状态与状态一致不错位")
    void concurrentReportAndRollbackNeverCorruptsState() throws Exception {
        logBanner("上报与回滚并发-竞速压测");

        DeviceRegistry registry = new DeviceRegistry();
        registerLightBrightness(registry);

        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(rounds);
        java.util.concurrent.ConcurrentLinkedQueue<String> mismatches =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int round = 0; round < rounds; round++) {
            final int r = round;
            // 每轮独立的 store/gateway/executor，避免幂等缓存影响；竞速只作用于“新上报确认 vs 回滚”。
            pool.submit(() -> {
                try {
                    start.await();
                    StateStore st = new StateStore();
                    DeviceGateway gw = new DeviceGateway();
                    ExecutionLog el = new ExecutionLog();
                    ConditionEvaluator ev = new ConditionEvaluator(st, registry);
                    ActionExecutor ex = new ActionExecutor(gw, registry, st, ev, el);

                    st.applyCommand("light", "brightness", 40L, 10L);
                    Map<String, DeviceState> pre = Map.of("light", st.snapshotOf("light"));
                    Action act = new Action("set-brightness", "light", "brightness", 80L);
                    ex.execute("rb-conc:" + r + ":set-brightness", act, List.of(), Map.of());

                    // 两个任务同时起跑：一个做“触发后新上报 60”，一个做“回滚到触发前 40”。
                    final CompensationStatus[] compResult = new CompensationStatus[1];
                    Thread rollback = new Thread(() ->
                            compResult[0] = ex.compensate("rb-conc:" + r, act, pre));
                    Thread report = new Thread(() ->
                            st.confirm("light", "brightness", 60L, 200L + r, 11L + r));
                    rollback.start();
                    report.start();
                    rollback.join();
                    report.join();

                    long queried = ((Number) st.snapshotOf("light").valueOf("brightness")).longValue();
                    int commands = gw.successfulCommandCount();
                    // 线性化不变量（两个线程都结束后读取，无读取竞态）：
                    // 1) 最终状态只能是 40（回滚线性化在上报之后）或 60（上报线性化在回滚之后），
                    //    绝不能停在动作值 80，也不能是其它值；
                    // 2) 最终=40 当且仅当回滚最后完成（RESTORED）；最终=60 时回滚可能 RESTORED
                    //    （先回滚、后到的上报确认）或 SUPERSEDED（先上报、回滚让位）——两者都合法，
                    //    关键是绝不出现“结论说回滚了但状态没跟上”：RESTORED 必有一次回滚物理命令，
                    //    SUPERSEDED 必无回滚命令；
                    // 3) 物理命令次数：动作 1 次 + RESTORED 时回滚 1 次（=2），SUPERSEDED 时=1。
                    if (queried != 40L && queried != 60L) {
                        mismatches.add("round " + r + " illegal final state=" + queried);
                    }
                    if (queried == 40L && compResult[0] != CompensationStatus.RESTORED) {
                        mismatches.add("round " + r + " final=40 but comp=" + compResult[0]);
                    }
                    if (compResult[0] == CompensationStatus.RESTORED && commands != 2) {
                        mismatches.add("round " + r + " RESTORED but commands=" + commands + " (expect 2)");
                    }
                    if (compResult[0] == CompensationStatus.SUPERSEDED_BY_NEW_REPORT && commands != 1) {
                        mismatches.add("round " + r + " SUPERSEDED but commands=" + commands + " (expect 1)");
                    }
                    if (compResult[0] == CompensationStatus.FAILED
                            || compResult[0] == CompensationStatus.ORIGINAL_UNAVAILABLE) {
                        mismatches.add("round " + r + " unexpected comp=" + compResult[0]);
                    }
                } catch (Exception e) {
                    mismatches.add("round " + r + " exception " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发竞速应在超时前完成");
        pool.shutdown();

        log.info("[并发竞速] rounds={} mismatches={}（期望0）", rounds, mismatches.size());
        assertTrue(mismatches.isEmpty(),
                "上报与回滚并发不得出现状态/结论错位：" + String.join(" | ", mismatches.stream().limit(10).toList()));
    }
}
