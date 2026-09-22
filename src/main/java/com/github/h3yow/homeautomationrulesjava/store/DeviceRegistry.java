package com.github.h3yow.homeautomationrulesjava.store;

import com.github.h3yow.homeautomationrulesjava.model.Device;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** 设备注册表：决定“未注册设备必须被拒绝”。线程安全，支持运行中注册。 */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();

    /** 注册设备；重复 id 视为编程错误直接拒绝，避免静默覆盖。 */
    public void register(Device device) {
        Device prev = devices.putIfAbsent(device.id(), device);
        if (prev != null) {
            throw new IllegalStateException("device already registered: " + device.id());
        }
    }

    public Device find(String deviceId) {
        return devices.get(deviceId);
    }

    public boolean exists(String deviceId) {
        return devices.containsKey(deviceId);
    }

    public Collection<Device> all() {
        return devices.values();
    }
}
