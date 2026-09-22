package com.github.h3yow.homeautomationrulesjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地 HTTP 端到端冒烟：确认 curl 可复现的接口行为与可区分错误码。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("设备查询、非法上报返回可区分错误码、合法上报被接受")
    void httpEndToEnd() {
        // 1) 设备列表
        var devices = rest.getForEntity("/api/devices", String.class);
        assertEquals(HttpStatus.OK, devices.getStatusCode());
        log.info("[HTTP] GET /api/devices -> {}", devices.getBody());

        // 2) 未注册设备上报：HTTP 200（事件被接收判定），业务结论为 REJECTED/UNKNOWN_DEVICE。
        ResponseEntity<Map> badDevice = rest.postForEntity("/api/events",
                Map.of("deviceId", "ghost", "property", "temperature", "value", 10,
                        "seq", 1, "eventId", "api-1", "eventTime", 1), Map.class);
        assertEquals(HttpStatus.OK, badDevice.getStatusCode());
        assertEquals("REJECTED", badDevice.getBody().get("outcome"));
        assertEquals("UNKNOWN_DEVICE", badDevice.getBody().get("rejectReason"));
        log.info("[HTTP] 未注册设备上报 body={}", badDevice.getBody());

        // 3) 非法值上报 -> 400（经接入服务返回 200 + REJECTED outcome，因为事件被业务接收判定为拒绝）
        //    事件结构合法时 HTTP 200，拒绝体现在 outcome/rejectReason，便于客户端区分处理。
        ResponseEntity<Map> badValue = rest.postForEntity("/api/events",
                Map.of("deviceId", "temp-sensor", "property", "temperature", "value", 999,
                        "seq", 1, "eventId", "api-2", "eventTime", 1), Map.class);
        assertEquals(HttpStatus.OK, badValue.getStatusCode());
        assertEquals("REJECTED", badValue.getBody().get("outcome"));
        assertEquals("INVALID_VALUE", badValue.getBody().get("rejectReason"));
        log.info("[HTTP] 非法值 outcome={} rejectReason={} decision={}",
                badValue.getBody().get("outcome"), badValue.getBody().get("rejectReason"),
                badValue.getBody().get("decision"));

        // 4) 合法上报越过阈值 -> ACCEPTED 并触发规则
        ResponseEntity<Map> ok = rest.postForEntity("/api/events",
                Map.of("deviceId", "temp-sensor", "property", "temperature", "value", 33,
                        "seq", 10, "eventId", "api-3", "eventTime", 10), Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals("ACCEPTED", ok.getBody().get("outcome"));
        log.info("[HTTP] 合法上报 decision={}", ok.getBody().get("decision"));

        // 5) 状态可查询
        ResponseEntity<Map> state = rest.getForEntity("/api/devices/plug/state", Map.class);
        assertEquals(HttpStatus.OK, state.getStatusCode());
        assertNotNull(state.getBody().get("confirmed"));
        log.info("[HTTP] plug 已确认状态={}", state.getBody());

        // 6) 动作轨迹可追溯
        ResponseEntity<String> actions = rest.getForEntity("/api/traces/actions", String.class);
        assertEquals(HttpStatus.OK, actions.getStatusCode());
        assertTrue(actions.getBody().contains("turn-off-plug"));
        log.info("[HTTP] 动作轨迹={}", actions.getBody());
    }
}
