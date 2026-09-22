package com.github.h3yow.homeautomationrulesjava.api;

import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.store.StateStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地查询接口：设备注册信息与已确认状态。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistry deviceRegistry;
    private final StateStore stateStore;

    public DeviceController(DeviceRegistry deviceRegistry, StateStore stateStore) {
        this.deviceRegistry = deviceRegistry;
        this.stateStore = stateStore;
    }

    @GetMapping
    public List<Map<String, Object>> listDevices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Device d : deviceRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name());
            m.put("type", d.type());
            m.put("properties", d.properties().keySet());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/{id}/state")
    public Map<String, Object> state(@PathVariable String id) {
        if (!deviceRegistry.exists(id)) {
            throw new ApiException("UNKNOWN_DEVICE", "device not registered: " + id);
        }
        DeviceState state = stateStore.snapshotOf(id);
        Map<String, Object> props = new LinkedHashMap<>();
        state.properties().forEach((k, v) -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("value", v.value());
            detail.put("lastSeq", v.lastSeq());
            detail.put("confirmedAt", v.confirmedAt());
            props.put(k, detail);
        });
        return Map.of("deviceId", id, "confirmed", props);
    }
}
