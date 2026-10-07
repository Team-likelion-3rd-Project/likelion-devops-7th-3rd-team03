# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace/redirect-service

# 소스 변경과 분리해 의존성 레이어를 캐시한다.
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle

# help는 설정/플러그인만 준비하고, 불필요한 테스트 의존성 해석은 피한다.
RUN --mount=type=cache,id=gradle-redirect,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew help --no-daemon --build-cache

COPY src ./src

RUN --mount=type=cache,id=gradle-redirect,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon --build-cache

# 콜드스타트 시 클래스 로딩(파싱/검증) 비용을 줄이기 위한 AppCDS 아카이브 생성.
# 빌드 시점엔 실제 DB/Redis가 없어서 앱이 끝까지는 못 뜨지만, hibernate.dialect를 명시해
# (실DB 없이는 못 하는) dialect 자동감지 단계를 건너뛰면 HikariCP 커넥션 생성 및 MySQL
# 드라이버 연결 코드까지 로딩된 뒤 "connection refused"로 실패한다 — Spring Framework/Boot
# 오토컨피그, Hibernate 메타모델 빌드, HikariCP까지 대부분의 무거운 클래스 로딩 구간을 커버.
RUN DB_HOST=127.0.0.1 DB_PORT=1 DB_NAME=cds DB_APP_USER=cds DB_APP_PASSWORD=cds \
    REDIS_HOST=127.0.0.1 REDIS_PORT=1 \
    timeout 20 java -XX:ArchiveClassesAtExit=app-cds.jsa \
      -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect \
      -jar app.jar || true

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/redirect-service/build/libs/*.jar app.jar
COPY --from=cds-trainer /app/app-cds.jsa app-cds.jsa

EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app-cds.jsa", "-Xshare:auto", "-jar", "app.jar"]
