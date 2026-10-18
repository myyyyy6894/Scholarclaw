# ============================================================================
# sc-java-backend 运行镜像（Java 21 + Spring Boot 3.3.x）
#
# ⚠️ 为什么不做多阶段 Maven 构建：
#    本机经代理访问 Maven 中央仓库实测仅约 20 KB/s，在容器内重新下载全部依赖需要数小时，
#    不可行。因此约定**先在本机构建可执行 jar**，再由本镜像 COPY：
#        mvn -f sc-java-backend/pom.xml -B -DskipTests package
#        docker compose build sc-java-backend
#    若在 CI 或网络良好的机器上构建，可改为多阶段：
#        FROM maven:3.9-eclipse-temurin-21 AS build
#        RUN mvn -B -DskipTests package
#        FROM eclipse-temurin:21-jre-alpine
#        COPY --from=build /workspace/target/*.jar /app/app.jar
# ============================================================================
FROM eclipse-temurin:21-jre-alpine

ARG APP_UID=10001
ARG APP_GID=10001
ARG JAR_FILE=target/sc-java-backend-1.0.0-SNAPSHOT.jar

# 非 root 运行（与 sc-python-ai 使用同一 uid/gid，便于共享卷的读写/只读权限对齐）
RUN addgroup -g ${APP_GID} -S app \
 && adduser -u ${APP_UID} -S -G app app

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

# 共享卷挂载点：先在镜像内创建并 chown。Docker 首次挂载空命名卷时会沿用镜像中该目录的
# 属主与权限；若不预置，非 root 进程对卷没有写权限（Java 需要读写并物理删除原始课件）。
RUN mkdir -p /data/scholarclaw/storage/raw \
 && chown -R ${APP_UID}:${APP_GID} /data/scholarclaw/storage

USER ${APP_UID}:${APP_GID}

EXPOSE 8080

# -Xmx768m 对齐 docs/02 §13.1 的「1024MB (-Xmx768m)」建议；容器内存上限由 compose 的 mem_limit 设定
ENV JAVA_OPTS="-Xmx768m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"

# 时区由 compose 注入的 TZ 环境变量固定（ENV-01），应用侧另由 sc.timezone 显式装载
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
