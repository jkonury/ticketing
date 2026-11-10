# 사전 준비: 저장소 루트에서 `./gradlew :fake-pg:bootJar` 실행 (이미지 안에서 Gradle을 돌리지 않는다)
FROM eclipse-temurin:25-jre

# 루트가 아닌 전용 사용자로 실행한다
RUN groupadd --system fakepg && useradd --system --gid fakepg --no-create-home fakepg

WORKDIR /app
COPY build/libs/fake-pg.jar app.jar

USER fakepg
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
