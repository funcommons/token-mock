# ---- 构建阶段: Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷 pom 下依赖,利用 Docker 层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- 运行阶段: JRE 21 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/target/token-mock.jar app.jar

ENV SERVER_PORT=9999 \
    JAVA_OPTS=""

EXPOSE 9999

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=$SERVER_PORT"]
