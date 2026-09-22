package com.github.h3yow.homeautomationrulesjava.engine;

import com.github.h3yow.homeautomationrulesjava.device.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.device.StateStore;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 对外门面：设备注册、事件上报、状态查询。 */
@Service
public class DeviceService {

    private final DeviceRegistry deviceRegistry;
    private final StateStore stateStore;
    private final RuleEngine ruleEngine;

    public DeviceService(DeviceRegistry deviceRegistry, StateStore stateStore, RuleEngine ruleEngine) {
        this.deviceRegistry = deviceRegistry;
        this.stateStore = stateStore;
        this.ruleEngine = ruleEngine;
    }

    public void register(DeviceSpec spec) {
        deviceRegistry.register(spec);
    }

    public RuleEngine.EngineResult report(EventReport event) {
        return ruleEngine.handle(event);
    }

    public Optional<DeviceState> state(String deviceId) {
        return stateStore.current(deviceId);
    }

    public List<DeviceState> states() {
        return stateStore.snapshot();
    }
}
