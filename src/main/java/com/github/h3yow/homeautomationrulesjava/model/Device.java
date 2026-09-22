package com.github.h3yow.homeautomationrulesjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 已注册设备：包含设备类型与它支持的全部属性规格。 */
public final class Device {

    private final String id;
    private final String name;
    private final DeviceType type;
    private final Map<String, PropertySpec> properties;

    public Device(String id, String name, DeviceType type, Map<String, PropertySpec> properties) {
        this.id = Objects.requireNonNull(id, "device id");
        this.name = name;
        this.type = Objects.requireNonNull(type, "device type");
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(properties, "properties")));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public DeviceType type() {
        return type;
    }

    public Map<String, PropertySpec> properties() {
        return properties;
    }

    public PropertySpec specOf(String property) {
        return properties.get(property);
    }
}
