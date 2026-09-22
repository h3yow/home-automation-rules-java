package com.github.h3yow.homeautomationrulesjava.device;

import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;

/**
 * 设备命令异常：把底层故障映射为可区分的失败原因
 * （DEVICE_UNREACHABLE / EXECUTION_TIMEOUT），供异常链路判定与重试策略使用。
 */
public class DeviceCommandException extends RuntimeException {

    private final ActionFailureReason reason;

    public DeviceCommandException(ActionFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeviceCommandException(ActionFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ActionFailureReason reason() {
        return reason;
    }
}
