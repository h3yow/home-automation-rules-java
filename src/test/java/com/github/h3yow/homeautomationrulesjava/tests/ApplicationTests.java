package com.github.h3yow.homeautomationrulesjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Spring 上下文可正常装配（所有 Bean 与演示数据初始化无冲突）。 */
@SpringBootTest
class ApplicationTests {

    @Test
    void contextLoads() {
        assertDoesNotThrow(() -> {
        });
    }
}
