# ============================================================
# 阶段 1 — Builder：编译打包
# ============================================================
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# 优先复制 pom.xml，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -q

# 再复制源码并打包（跳过测试）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -DskipTests -q

# ============================================================
# 阶段 2 — Runtime：运行时镜像（最小体积）
# ============================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

# 创建非 root 用户，提升安全性
RUN addgroup -S webssh && adduser -S webssh -G webssh

WORKDIR /app

# 从 Builder 阶段复制 JAR
COPY --from=builder /build/target/*.jar app.jar

# 数据目录（会话 JSON 持久化）
RUN mkdir -p /app/data/sessions && chown -R webssh:webssh /app

USER webssh

# 声明数据卷（docker-compose 中用 bind mount 覆盖）
VOLUME ["/app/data"]

EXPOSE 8080

# JVM 参数可通过 JAVA_OPTS 环境变量覆盖
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
