package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import java.util.List;
import java.util.Optional;

/** 设备注册表：登记设备及其合法取值范围。 */
public interface DeviceRegistry {
    void register(DeviceSpec spec);

    Optional<DeviceSpec> find(String deviceId);

    List<DeviceSpec> all();
}
