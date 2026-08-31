# token-mock 运行时镜像。
# jar 由构建方提前产出:先 `mvn -DskipTests package`,再 `docker build`。
# (CI 的 release.yml 即按此顺序构建 amd64/arm64 双架构镜像)
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY target/token-mock.jar app.jar

ENV SERVER_PORT=9999 \
    JAVA_OPTS=""

EXPOSE 9999

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=$SERVER_PORT"]
