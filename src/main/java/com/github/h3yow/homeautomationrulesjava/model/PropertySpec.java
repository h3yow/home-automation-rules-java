package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Objects;

/**
 * 设备属性规格：声明属性的取值类型与（可选的）数值范围。
 * 所有上报值与动作下发值都必须先通过 normalize 校验，保证非法取值无法进入系统。
 */
public final class PropertySpec {

    private final String name;
    private final PropertyType type;
    /** 数值类型的可选下界（含），非数值类型忽略。 */
    private final Double min;
    /** 数值类型的可选上界（含），非数值类型忽略。 */
    private final Double max;

    public PropertySpec(String name, PropertyType type, Double min, Double max) {
        this.name = Objects.requireNonNull(name, "property name");
        this.type = Objects.requireNonNull(type, "property type");
        this.min = min;
        this.max = max;
    }

    public static PropertySpec bool(String name) {
        return new PropertySpec(name, PropertyType.BOOLEAN, null, null);
    }

    public static PropertySpec integer(String name, Long min, Long max) {
        return new PropertySpec(name, PropertyType.INTEGER,
                min == null ? null : min.doubleValue(),
                max == null ? null : max.doubleValue());
    }

    public static PropertySpec decimal(String name, Double min, Double max) {
        return new PropertySpec(name, PropertyType.DECIMAL, min, max);
    }

    public static PropertySpec string(String name) {
        return new PropertySpec(name, PropertyType.STRING, null, null);
    }

    public String name() {
        return name;
    }

    public PropertyType type() {
        return type;
    }

    /**
     * 将外部输入（JSON 反序列化后通常为 Boolean/Long/Double/String）规范化为属性声明的类型，
     * 并执行范围校验。非法取值抛 IllegalArgumentException，由上层映射为 INVALID_VALUE。
     */
    public Object normalize(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("property '" + name + "' value is null");
        }
        switch (type) {
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                if (raw instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                    return Boolean.parseBoolean(s);
                }
                throw new IllegalArgumentException("property '" + name + "' expects boolean but got " + raw);
            }
            case INTEGER -> {
                long v = parseLong(raw);
                checkRange(v);
                return v;
            }
            case DECIMAL -> {
                double v = parseDouble(raw);
                checkRange(v);
                return v;
            }
            case STRING -> {
                if (!(raw instanceof String s) || s.isBlank()) {
                    throw new IllegalArgumentException("property '" + name + "' expects non-blank string");
                }
                return s;
            }
            default -> throw new IllegalStateException("unknown type " + type);
        }
    }

    private long parseLong(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException("property '" + name + "' expects integer but got " + raw);
            }
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("property '" + name + "' expects integer but got '" + s + "'");
            }
        }
        throw new IllegalArgumentException("property '" + name + "' expects integer but got " + raw);
    }

    private double parseDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("property '" + name + "' expects number but got '" + s + "'");
            }
        }
        throw new IllegalArgumentException("property '" + name + "' expects number but got " + raw);
    }

    private void checkRange(double v) {
        if (min != null && v < min) {
            throw new IllegalArgumentException("property '" + name + "' value " + v + " below min " + min);
        }
        if (max != null && v > max) {
            throw new IllegalArgumentException("property '" + name + "' value " + v + " above max " + max);
        }
    }
}
