package com.github.h3yow.homeautomationrulesjava.api;

import com.github.h3yow.homeautomationrulesjava.device.DeviceGateway;
import com.github.h3yow.homeautomationrulesjava.engine.EventIngestService;
import com.github.h3yow.homeautomationrulesjava.model.ActionFailureReason;
import com.github.h3yow.homeautomationrulesjava.model.IngestResult;
import com.github.h3yow.homeautomationrulesjava.store.ExecutionLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件上报与可观测接口（全部本地）。
 */
@RestController
@RequestMapping("/api")
public class EventController {

    /** 上报请求体。 */
    public record EventRequest(
            String deviceId,
            String property,
            Object value,
            Long seq,
            String eventId,
            Long eventTime
    ) {
    }

    /** 故障注入请求体。 */
    public record FaultRequest(
            String deviceId,
            String property,
            String reason,        // DEVICE_UNREACHABLE / EXECUTION_TIMEOUT
            Integer failTimes     // null 或 <=0 表示永久故障；>0 表示瞬时失败次数
    ) {
    }

    private final EventIngestService ingestService;
    private final ExecutionLog executionLog;
    private final DeviceGateway deviceGateway;

    public EventController(EventIngestService ingestService, ExecutionLog executionLog,
                           DeviceGateway deviceGateway) {
        this.ingestService = ingestService;
        this.executionLog = executionLog;
        this.deviceGateway = deviceGateway;
    }

    /** 上报一条设备状态事件，返回 outcome 与可解释判定依据。 */
    @PostMapping("/events")
    public IngestResult report(@RequestBody EventRequest req) {
        long eventTime = req.eventTime() != null ? req.eventTime() : System.currentTimeMillis();
        return ingestService.ingest(req.deviceId(), req.property(), req.value(),
                req.seq(), req.eventId(), eventTime);
    }

    /** 查询全部上报判定轨迹。 */
    @GetMapping("/traces/events")
    public List<ExecutionLog.EventTrace> eventTraces() {
        return executionLog.eventTraces();
    }

    /** 查询全部规则触发轨迹。 */
    @GetMapping("/traces/fires")
    public List<ExecutionLog.FireTrace> fireTraces() {
        return executionLog.fireTraces();
    }

    /** 查询全部动作执行记录（可断言动作次数/原因/补偿）。 */
    @GetMapping("/traces/actions")
    public List<Map<String, Object>> actionTraces() {
        return executionLog.actionRecords().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idempotencyKey", r.idempotencyKey());
            m.put("target", r.action().targetDeviceId() + "." + r.action().property());
            m.put("status", r.status());
            m.put("attempts", r.attempts());
            m.put("failureReason", r.failureReason());
            m.put("compensated", r.compensated());
            m.put("errors", r.errors());
            return m;
        }).toList();
    }

    /** 测试辅助：注入设备故障（不可达/超时；永久/瞬时）。 */
    @PostMapping("/test/faults")
    public Map<String, String> injectFault(@RequestBody FaultRequest req) {
        ActionFailureReason reason;
        try {
            reason = ActionFailureReason.valueOf(req.reason());
        } catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_REASON",
                    "reason must be DEVICE_UNREACHABLE or EXECUTION_TIMEOUT");
        }
        if (req.failTimes() != null && req.failTimes() > 0) {
            deviceGateway.failTransiently(req.deviceId(), req.property(), reason, req.failTimes());
        } else {
            deviceGateway.failPermanently(req.deviceId(), req.property(), reason);
        }
        return Map.of("status", "fault injected: " + req.deviceId() + "." + req.property()
                + " reason=" + reason + " times=" + req.failTimes());
    }

    /** 测试辅助：设备恢复。 */
    @PostMapping("/test/recover")
    public Map<String, String> recover(@RequestBody FaultRequest req) {
        deviceGateway.recover(req.deviceId(), req.property());
        return Map.of("status", "recovered: " + req.deviceId() + "." + req.property());
    }
}
