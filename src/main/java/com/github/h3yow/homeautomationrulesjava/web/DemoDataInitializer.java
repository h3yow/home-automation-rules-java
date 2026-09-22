package com.github.h3yow.homeautomationrulesjava.web;

import com.github.h3yow.homeautomationrulesjava.device.DeviceRegistry;
import com.github.h3yow.homeautomationrulesjava.engine.RuleService;
import com.github.h3yow.homeautomationrulesjava.model.DeviceSpec;
import com.github.h3yow.homeautomationrulesjava.model.DeviceType;
import com.github.h3yow.homeautomationrulesjava.rule.ActionDef;
import com.github.h3yow.homeautomationrulesjava.rule.Condition;
import com.github.h3yow.homeautomationrulesjava.rule.Operator;
import com.github.h3yow.homeautomationrulesjava.rule.Rule;
import com.github.h3yow.homeautomationrulesjava.rule.TriggerOn;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 本地演示数据：启动后注册一组温度传感器/风扇设备与一条高温规则。
 * 仅使用内存数据，无任何外部依赖。
 */
@Configuration
public class DemoDataInitializer {

    @Bean
    ApplicationRunner demoData(DeviceRegistry registry, RuleService rules) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                registerIfAbsent(registry, new DeviceSpec(
                        "temp-1", DeviceType.SENSOR, -40d, 80d));
                registerIfAbsent(registry, new DeviceSpec(
                        "motion-1", DeviceType.SENSOR, 0d, 1d));
                registerIfAbsent(registry, new DeviceSpec(
                        "fan-1", DeviceType.SWITCH, 0d, 1d));
                registerIfAbsent(registry, new DeviceSpec(
                        "fan-1-rst", DeviceType.SWITCH, 0d, 1d));
                registerIfAbsent(registry, new DeviceSpec(
                        "lamp-1", DeviceType.SWITCH, 0d, 1d));
                registerIfAbsent(registry, new DeviceSpec(
                        "lamp-1-rst", DeviceType.SWITCH, 0d, 1d));

                if (rules.all().stream().noneMatch(r -> r.id().equals("rule-cooling"))) {
                    rules.add(new Rule("rule-cooling", "高温开风扇",
                            "temp-1", TriggerOn.STATE_CHANGE,
                            List.of(new Condition("temp-1", Operator.GT, 30d)),
                            List.of(new ActionDef("fan-1", 1d, false)),
                            List.of(new ActionDef("fan-1-rst", 0d, true))));
                }
            }

            private void registerIfAbsent(DeviceRegistry registry, DeviceSpec spec) {
                if (registry.find(spec.deviceId()).isEmpty()) {
                    registry.register(spec);
                }
            }
        };
    }
}
