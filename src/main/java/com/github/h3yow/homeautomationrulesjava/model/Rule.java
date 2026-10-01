package com.github.h3yow.homeautomationrulesjava.model;

import java.util.List;
import java.util.Objects;

/**
 * 自动化规则：所有 conditions 同时成立（AND）时触发，按顺序执行 actions。
 *
 * 触发语义为边沿触发：条件组由“不满足”变为“满足”的那一次状态确认才触发一次；
 * 条件持续满足期间的重复上报不重复触发。
 *
 * enabled / actions 等参数支持运行中调整，每次调整 version 加一，
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
     * 最短触发间隔（毫秒）。null 表示不启用冷却，行为与历史完全一致（仅边沿触发）。
     * 非 null 且 > 0 时：一次实际触发后进入冷却，冷却期内出现的 false→true 边沿
     * 一律不触发；冷却结束之后出现的下一次 false→true 边沿才正常触发。
     */
    private final Long cooldownMillis;

    /** 兼容旧签名：不设置冷却（cooldownMillis=null）。 */
    public Rule(String id, String name, boolean enabled,
                List<Condition> conditions, List<Action> actions, long version) {
        this(id, name, enabled, conditions, actions, version, null);
    }

    public Rule(String id, String name, boolean enabled,
                List<Condition> conditions, List<Action> actions, long version, Long cooldownMillis) {
        this.id = Objects.requireNonNull(id, "rule id");
        this.name = name;
        this.enabled = enabled;
        this.conditions = List.copyOf(Objects.requireNonNull(conditions));
        this.actions = List.copyOf(Objects.requireNonNull(actions));
        this.version = version;
        this.cooldownMillis = normalizeCooldown(cooldownMillis);
    }

    private static Long normalizeCooldown(Long cooldownMillis) {
        if (cooldownMillis == null || cooldownMillis <= 0L) {
            return null;
        }
        return cooldownMillis;
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

    /** 最短触发间隔（毫秒）；null 表示未启用冷却。 */
    public Long cooldownMillis() {
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

    /**
     * 运行中调整冷却：返回新版本副本。
     * 传入 null 或 &lt;=0 表示关闭冷却；正数表示最短触发间隔（毫秒）。
     * 调整立即对后续边沿评估生效，不改变“上次实际触发时刻”，因此冷却窗口
     * 永远从最近一次实际触发时刻起算（可预测，不随配置变更漂移）。
     */
    public Rule withCooldownMillis(Long newCooldownMillis) {
        return new Rule(id, name, enabled, conditions, actions, version + 1,
                normalizeCooldown(newCooldownMillis));
    }
}
