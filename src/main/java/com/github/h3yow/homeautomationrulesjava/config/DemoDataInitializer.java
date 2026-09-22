package com.github.h3yow.homeautomationrulesjava.config;

import com.github.h3yow.homeautomationrulesjava.engine.RuleAdminService;
import com.github.h3yow.homeautomationrulesjava.model.Action;
import com.github.h3yow.homeautomationrulesjava.model.Condition;
import com.github.h3yow.homeautomationrulesjava.model.Device;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.model.Operator;
import com.github.h3yow.homeautomationrulesjava.model.PropertySpec;
import com.github.h3yow.homeautomationrulesjava.store.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据初始化：纯本地构造，无外部依赖。
 * 场景：温度传感器 temperature 高于 30 度时，关闭 plug 并（尝试）上锁 lock。
 */
@Configuration
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Bean
    public ApplicationRunner demoDataRunner(DeviceRegistry registry, RuleAdminService ruleAdmin) {
        return args -> {
            registerDevices(registry);
            ruleAdmin.createRule(
                    "rule-cool-down",
                    "高温自动断电",
                    true,
                    List.of(new Condition("temp-sensor", "temperature", Operator.GT, 30)),
                    List.of(
                            new Action("turn-off-plug", "plug", "on", false),
                            new Action("lock-door", "lock", "locked", true)
                    )
            );
            log.info("演示数据初始化完成: 设备 temp-sensor/plug/lock, 规则 rule-cool-down");
        };
    }

    private void registerDevices(DeviceRegistry registry) {
        Map<String, PropertySpec> tempProps = new LinkedHashMap<>();
        tempProps.put("temperature", PropertySpec.decimal("temperature", -50.0, 100.0));
        registry.register(new Device("temp-sensor", "客厅温度传感器",
                DeviceType.TEMPERATURE_SENSOR, tempProps));

        Map<String, PropertySpec> plugProps = new LinkedHashMap<>();
        plugProps.put("on", PropertySpec.bool("on"));
        registry.register(new Device("plug", "智能插座", DeviceType.PLUG, plugProps));

        Map<String, PropertySpec> lockProps = new LinkedHashMap<>();
        lockProps.put("locked", PropertySpec.bool("locked"));
        registry.register(new Device("lock", "门锁", DeviceType.LOCK, lockProps));

        Map<String, PropertySpec> lightProps = new LinkedHashMap<>();
        lightProps.put("on", PropertySpec.bool("on"));
        lightProps.put("brightness", PropertySpec.integer("brightness", 0L, 100L));
        registry.register(new Device("light", "客厅灯", DeviceType.LIGHT, lightProps));
    }
}
