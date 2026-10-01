package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;
import java.util.Objects;

/**
 * 自动化规则：所有 conditions 同时成立（AND）时触发，按顺序执行 actions。
 *
 * 触发语义为边沿触发：条件组由“不满足”变为“满足”的那一次状态确认才触发一次；
 * 条件持续满足期间的重复上报不重复触发。
 *
 * enabled / actions / cooldownMillis 等参数支持运行中调整，每次调整 version 加一，
 * 调整动作通过整体替换引用生效（新规则读新定义、在途执行读旧定义）。
 */
public final class Rule {

    private final String id;
    private final String name;
    private final boolean enabled;
    private final List<Condition> conditions;
    private final List<Action> actions;
    private final long version;
    /**
     * 最短触发间隔（冷却，毫秒）。0 表示不配置冷却，边沿触发行为与旧版本完全一致。
     * 计时口径为引擎处理时刻：一次成功触发后，[fireAt, fireAt + cooldownMillis) 内
     * 出现的 false→true 边沿一律被挡下；冷却结束后再次出现的边沿才允许触发。
     */
    private final long cooldownMillis;

    public Rule(String id, String name, boolean enabled,
                List<Condition> conditions, List<Action> actions, long version) {
        this(id, name, enabled, conditions, actions, version, 0L);
    }

    public Rule(String id, String name, boolean enabled,
                List<Condition> conditions, List<Action> actions, long version, long cooldownMillis) {
        this.id = Objects.requireNonNull(id, "rule id");
        this.name = name;
        this.enabled = enabled;
        this.conditions = List.copyOf(Objects.requireNonNull(conditions));
        this.actions = List.copyOf(Objects.requireNonNull(actions));
        this.version = version;
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("cooldownMillis must be >= 0: " + cooldownMillis);
        }
        this.cooldownMillis = cooldownMillis;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    public List<Action> actions() {
        return actions;
    }

    public long version() {
        return version;
    }

    /** 最短触发间隔（冷却，毫秒）；0 表示未配置冷却。 */
    public long cooldownMillis() {
        return cooldownMillis;
    }

    /** 运行中启停：返回新版本副本。 */
    public Rule withEnabled(boolean newEnabled) {
        return new Rule(id, name, newEnabled, conditions, actions, version + 1, cooldownMillis);
    }

    /** 运行中参数调整：返回新版本副本。 */
    public Rule withDefinition(String newName, List<Condition> newConditions, List<Action> newActions) {
        return new Rule(id, newName, enabled,
                List.copyOf(newConditions), List.copyOf(newActions), version + 1, cooldownMillis);
    }

    /** 运行中调整冷却（调长/调短/置 0 关闭）：返回新版本副本。 */
    public Rule withCooldown(long newCooldownMillis) {
        return new Rule(id, name, enabled, conditions, actions, version + 1, newCooldownMillis);
    }
}
