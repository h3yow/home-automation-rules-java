package com.github.h3yow.homeautomationrulesjava.action;

/** 设备执行网关抽象。 */
public interface DeviceGateway {
    void execute(ActionCommand command);
}
