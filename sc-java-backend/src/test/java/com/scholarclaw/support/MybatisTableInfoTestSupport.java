package com.scholarclaw.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯 Mockito 单测不会启动 MyBatis，`LambdaUpdateWrapper` 因此找不到实体元数据。
 *
 * <p>此前这类测试依赖整个测试 JVM 的执行顺序（先跑了某个 Spring 上下文测试）才能通过；
 * 新增测试类改变顺序后就会集中报 `can not find lambda cache`。显式初始化可消除顺序依赖。
 */
public final class MybatisTableInfoTestSupport {

    private MybatisTableInfoTestSupport() {
    }

    public static void init(Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }
}
