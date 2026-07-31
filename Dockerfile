# 1단계: 빌드 (Gradle + JDK)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행 (JRE 만 포함한 경량 이미지)
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# HEALTHCHECK 용. eclipse-temurin JRE 이미지에는 curl/wget 이 기본으로 없다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

# start-period 는 최초 기동(Flyway 마이그레이션 포함)에 걸리는 시간을 감안한 여유다.
# /actuator/health 는 SecurityConfig 에서 permitAll 이라 인증 없이 호출된다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
