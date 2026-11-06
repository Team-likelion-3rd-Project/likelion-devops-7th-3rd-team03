# syntax=docker/dockerfile:1.7

# jar 는 CPU 와 무관하다. Gradle 빌드는 러너 플랫폼에서 한 번만 한다.
# (arm64 이미지도 이 jar 를 그대로 쓴다. Gradle 을 QEMU 로 돌리면 매우 느리다)
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build

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

# DuckDB httpfs 확장을 빌드 시점에 받아 둔다.
# 온프렘 런타임은 외부 인터넷이 막혀 있을 수 있어, 배치 실행 중 다운로드에 의존하지 않는다.
# 확장은 CPU 별 파일(linux_amd64 / linux_arm64)이라 대상 플랫폼에서 설치한다 (arm64 는 QEMU 로 돈다).
# 앱 jar 안의 duckdb_jdbc를 꺼내 jshell로 INSTALL을 실행한다 (DuckDB 버전과 확장 버전이 자동으로 일치).
FROM eclipse-temurin:17-jdk AS duckdb-ext

COPY --from=build /workspace/build/libs/ /tmp/app/
RUN <<'EOF'
set -e
mkdir -p /duckdb-extensions /tmp/duckdb-lib
cd /tmp/duckdb-lib
jar xf /tmp/app/*.jar BOOT-INF/lib
DUCKDB_JAR=$(ls /tmp/duckdb-lib/BOOT-INF/lib/duckdb_jdbc-*.jar)
cat > /tmp/install.jsh <<'JSH'
var connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
var statement = connection.createStatement();
statement.execute("SET extension_directory = '/duckdb-extensions'");
statement.execute("INSTALL httpfs");
/exit
JSH
jshell --class-path "$DUCKDB_JAR" /tmp/install.jsh
# jshell은 예외가 나도 정상 종료하므로, 파일이 실제로 생겼는지 직접 확인해 빌드를 실패시킨다
find /duckdb-extensions -name 'httpfs.duckdb_extension' | grep -q . \
  || (echo "httpfs 확장 설치 실패" && exit 1)
EOF

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
# 미리 받아둔 확장을 런타임 이미지에 넣고, 앱이 그 위치를 쓰게 한다
COPY --from=duckdb-ext /duckdb-extensions /app/duckdb-extensions
ENV DUCKDB_EXTENSION_DIRECTORY=/app/duckdb-extensions

# /actuator/info 에 보여줄 빌드 커밋. CI 가 --build-arg GIT_SHA=<커밋> 으로 넘긴다.
# 빌드 컨텍스트(src/management-service)에 .git 이 없어서 git 플러그인 대신 빌드 인자로 받는다.
ARG GIT_SHA=unknown
ENV APP_COMMIT=$GIT_SHA

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]