# 앱 이미지. 테스트는 CI와 ./gradlew test가 맡고, 이미지는 실행 파일만 만든다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 빌드 설정을 먼저 복사해 의존성을 받아 두면, 소스만 바뀐 빌드는 이 레이어를 재사용한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
# 컨테이너 헬스체크(readiness)용 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app/build/libs/flashdeal-*.jar app.jar
# root가 아닌 사용자로 실행한다
USER app
EXPOSE 8080
# 컨테이너 메모리 한도의 75%까지 힙으로 쓴다 (고정 -Xmx를 두면 작은 서버에서 OOM으로 죽는다)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# readiness: DB 연결까지 준비돼야 UP. 프록시와 compose의 depends_on이 이 상태를 본다
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
# exec로 java를 PID 1로 만들어야 docker stop의 SIGTERM이 전달되고 graceful shutdown이 동작한다
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
