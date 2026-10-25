package com.scholarclaw.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * multipart 上限必须绑定在 `spring.servlet.multipart`。
 *
 * <p>放到 `server.servlet.multipart` 不会报错，但 Spring Boot 会忽略并回退到 1MB 默认值，
 * 表现为几 MB 的课件上传也返回 `FILE_SIZE_EXCEEDED`。这里直接校验配置键，防止再次放错层级。
 */
class MultipartConfigTest {

    @Test
    @DisplayName("上传上限配置在 spring.servlet.multipart 且为 50MB/51MB")
    void multipartLimitsAreBoundUnderSpringNamespace() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(property(sources, "spring.servlet.multipart.max-file-size")).isEqualTo("50MB");
        assertThat(property(sources, "spring.servlet.multipart.max-request-size")).isEqualTo("51MB");
        assertThat(property(sources, "server.servlet.multipart.max-file-size")).isNull();
    }

    private static Object property(List<PropertySource<?>> sources, String key) {
        return sources.stream()
                .map(source -> source.getProperty(key))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
