# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

# 의존성 정의를 소스보다 먼저 복사한다. 애플리케이션 코드만 변경되면 이 레이어는 재사용된다.
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle

# Docker 레이어 캐시가 무효화돼도 Gradle 배포본·의존성·태스크 캐시는 재사용한다.
# help는 설정/플러그인만 준비하므로, 테스트 의존성까지 전부 해석하지 않는다.
RUN --mount=type=cache,id=gradle-management,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew help --no-daemon --build-cache

COPY src ./src

RUN --mount=type=cache,id=gradle-management,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon --build-cache

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
