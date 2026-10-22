package com.scholarclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ScholarClaw 业务与状态权威中心（Java 模块化单体）启动入口。
 *
 * <p>本服务是 MySQL 的唯一连接方与 {@code SC_MASTER_KEY} 的唯一持有者；模块间依赖为
 * 包级 Strict DAG，由 {@code com.scholarclaw.architecture.ModuleDependencyTest} 可执行断言守护，
 * 分层与依赖方向定义见 {@code 02_系统架构文档} §4.1 / §4.3。
 */
@SpringBootApplication
@EnableScheduling
public class ScholarClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScholarClawApplication.class, args);
    }
}
