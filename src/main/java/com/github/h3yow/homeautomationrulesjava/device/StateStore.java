package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.DeviceState;
import com.github.h3yow.homeautomationrulesjava.model.EventReceipt;
import com.github.h3yow.homeautomationrulesjava.model.EventReport;
import java.util.List;
import java.util.Optional;

/**
 * 设备状态存储：负责事件去重、乱序/迟到防护、版本推进。
 */
public interface StateStore {
    EventReceipt accept(EventReport event);

    Optional<DeviceState> current(String deviceId);

    List<DeviceState> snapshot();
}
