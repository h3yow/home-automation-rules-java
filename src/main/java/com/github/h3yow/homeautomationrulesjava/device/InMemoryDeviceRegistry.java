package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 基于内存 Map 的设备注册表。 */
@SuppressWarnings("unused")
@Component
public class InMemoryDeviceRegistry implements DeviceRegistry {
    private final ConcurrentMap<String, DeviceSpec> devices = new ConcurrentHashMap<>();

    @Override
    public void register(DeviceSpec spec) {
        if (spec == null || spec.deviceId() == null || spec.deviceId().isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (spec.type() == null) {
            throw new IllegalArgumentException("device type must not be null");
        }
        if (Double.isNaN(spec.minValue()) || Double.isNaN(spec.maxValue())
                || spec.minValue() > spec.maxValue()) {
            throw new IllegalArgumentException(
                    "illegal value range: [" + spec.minValue() + ", " + spec.maxValue() + "]");
        }
        DeviceSpec previous = devices.putIfAbsent(spec.deviceId(), spec);
        if (previous != null) {
            throw new IllegalArgumentException("device already registered: " + spec.deviceId());
        }
    }

    @Override
    public Optional<DeviceSpec> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    @Override
    public List<DeviceSpec> all() {
        return devices.values().stream()
                .sorted(java.util.Comparator.comparing(DeviceSpec::deviceId))
                .toList();
    }

    /** 测试辅助：清空注册表。 */
    public void clear() {
        devices.clear();
    }

    /** 便捷注册开关量设备（0/1）。 */
    public DeviceSpec registerSwitch(String deviceId) {
        DeviceSpec spec = new DeviceSpec(deviceId, DeviceType.SWITCH, 0d, 1d);
        register(spec);
        return spec;
    }
}
