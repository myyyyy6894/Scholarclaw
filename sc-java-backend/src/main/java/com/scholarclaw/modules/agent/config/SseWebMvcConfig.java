package com.scholarclaw.modules.agent.config;

import com.scholarclaw.modules.agent.controller.SseExceptionResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 把 SSE 专用异常解析器排到默认解析器之前（TASK-604 复核修复）。 */
@Configuration
public class SseWebMvcConfig implements WebMvcConfigurer {

    private final SseExceptionResolver sseExceptionResolver;

    public SseWebMvcConfig(SseExceptionResolver sseExceptionResolver) {
        this.sseExceptionResolver = sseExceptionResolver;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, sseExceptionResolver);
    }
}
