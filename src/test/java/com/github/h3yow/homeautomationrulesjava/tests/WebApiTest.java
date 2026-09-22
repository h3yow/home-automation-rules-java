package com.github.h3yow.homeautomationrulesjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 层本地冒烟：事件上报、拒绝原因、审计查询。 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("HTTP 上报正常事件/重复事件/非法设备，结论可在接口上断言")
    void reportAndAuditOverHttp() throws Exception {
        // 正常上报（temp-1 来自演示数据，范围 -40..80）
        MvcResult ok = mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"web-1","deviceId":"temp-1","value":35.0,"eventTime":900000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("ACCEPTED"))
                .andReturn();

        // 重复事件
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"web-1","deviceId":"temp-1","value":35.0,"eventTime":900000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("DUPLICATE"));

        // 未注册设备
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"web-2","deviceId":"no-such-device","value":1.0,"eventTime":900001}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("REJECTED"))
                .andExpect(jsonPath("$.receipt.reason").value("DEVICE_NOT_REGISTERED"));

        // 审计接口
        MvcResult audit = mockMvc.perform(get("/api/audit/events"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode records = json.readTree(audit.getResponse().getContentAsString());
        assertEquals(3, records.size());
        assertEquals("web-1", records.get(0).get("event").get("eventId").asText());
    }
}
