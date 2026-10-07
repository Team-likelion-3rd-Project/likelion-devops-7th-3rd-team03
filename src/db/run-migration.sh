#!/usr/bin/env bash
#
# run-migration.sh
# 00-database-setup.sql 실행 후, V*.sql 마이그레이션을 버전 순서대로 적용한다.
#
# 사용법:
#   ./run-migration.sh                 # .env 또는 기본값(로컬 도커)
#   ./run-migration.sh --status        # 적용 이력만 조회하고 종료
#   ./run-migration.sh --skip-setup    # 00 건너뛰고 V*.sql만 적용
#   ./run-migration.sh --allow-non-utc # time_zone이 UTC가 아니어도 경고만 하고 진행
#   DB_HOST=xxx.rds.amazonaws.com DB_ROOT_USER=admin DB_ROOT_PASSWORD=... ./run-migration.sh
#                                 # RDS는 마스터 계정명이 'root'가 아닌 경우가 많다(.env.example 참조).
#                                 # DB_ROOT_USER를 생략하면 기본값 'root'로 접속을 시도해 인증 실패한다.
#
# time_zone 검증:
#   click_events.clicked_at 등 DATETIME 컬럼은 DB의 time_zone 설정을 그대로 따른다
#   (TIMESTAMP와 달리 UTC로 자동 변환되지 않음). @@global.time_zone이 UTC/+00:00이
#   아니면 02-stats-queries.sql의 KST<->UTC 집계 로직이 조용히 틀어지므로, 이 스크립트는
#   접속 직후 time_zone을 검증하고 UTC가 아니면 기본적으로 중단한다.
#   로컬 개발에서 의도적으로 다른 타임존을 쓴다면 --allow-non-utc 또는
#   ALLOW_NON_UTC=1 로 이 검증을 경고로 완화할 수 있다.
#
# 동작 방식:
#   schema_history 테이블에 적용 이력을 남기고, 이미 성공한 버전은 건너뛴다.
#   그래서 여러 번 실행해도 안전하다 (V1을 두 번 돌려 "table already exists"로
#   깨지는 사고를 막는다). Flyway를 그대로 축소한 형태다.
#
#   실제 배포에서는 Spring Boot의 Flyway가 이 역할을 한다.
#   이 스크립트는 로컬 개발과 수동 점검용이다.
#
# 02-stats-queries.sql은 실행하지 않는다.
#   :userId 같은 바인딩 파라미터가 있어 그대로 돌리면 문법 오류가 난다.
#   애플리케이션 Repository로 옮겨 담을 쿼리 모음이다.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------------------------------------------------------------------
# 인자
# ---------------------------------------------------------------------
STATUS_ONLY=0
SKIP_SETUP=0
ALLOW_NON_UTC="${ALLOW_NON_UTC:-0}"
for arg in "$@"; do
    case "$arg" in
        --status)         STATUS_ONLY=1 ;;
        --skip-setup)     SKIP_SETUP=1 ;;
        --allow-non-utc)  ALLOW_NON_UTC=1 ;;
        *) echo "[ERROR] 알 수 없는 옵션: $arg"; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------
# 설정 — .env가 있으면 덮어쓴다
# ---------------------------------------------------------------------
[ -f .env ] && set -a && . ./.env && set +a

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-shortlink}"
DB_ROOT_USER="${DB_ROOT_USER:-root}"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-root}"

# 00-database-setup.sql이 실제로 만드는 계정명은 DB_NAME과 무관하게 고정('shortlink_migrator').
# 예전엔 "${DB_NAME}_migrator"로 유도해 썼는데, DB_NAME을 shortlink 외 값으로 바꾸면
# 00-database-setup.sql의 고정 계정명과 어긋나 조용히 인증 실패한다. 그래서 별도 변수로 분리한다.
DB_MIGRATOR_USER="${DB_MIGRATOR_USER:-shortlink_migrator}"

APP_PASSWORD="${APP_PASSWORD:-app_local_pw}"
MIGRATOR_PASSWORD="${MIGRATOR_PASSWORD:-migrator_local_pw}"
READONLY_PASSWORD="${READONLY_PASSWORD:-readonly_local_pw}"

# ---------------------------------------------------------------------
# 사전 점검
# ---------------------------------------------------------------------
command -v mysql >/dev/null 2>&1 || {
    echo "[ERROR] mysql 클라이언트가 없습니다."
    echo "        도커라면: docker compose exec -T mysql mysql -uroot -proot < 00-database-setup.sql"
    exit 1
}

# 운영 환경에서 로컬 기본 비밀번호가 그대로 쓰이는 사고를 막는다
# APP_PASSWORD 하나만 검사하면, 그것만 바꾸고 나머지(MIGRATOR/READONLY/ROOT)를
# 로컬 기본값으로 남겨둔 채 원격 배포를 통과시킬 수 있다. 네 계정 전부 검사한다.
if [ "$DB_HOST" != "127.0.0.1" ] && [ "$DB_HOST" != "localhost" ]; then
    DEFAULTED=""
    [ "$APP_PASSWORD" = "app_local_pw" ]           && DEFAULTED="$DEFAULTED APP_PASSWORD"
    [ "$MIGRATOR_PASSWORD" = "migrator_local_pw" ] && DEFAULTED="$DEFAULTED MIGRATOR_PASSWORD"
    [ "$READONLY_PASSWORD" = "readonly_local_pw" ] && DEFAULTED="$DEFAULTED READONLY_PASSWORD"
    [ "$DB_ROOT_PASSWORD" = "root" ]               && DEFAULTED="$DEFAULTED DB_ROOT_PASSWORD"

    if [ -n "$DEFAULTED" ]; then
        echo "[ERROR] 원격 호스트($DB_HOST)인데 다음 값이 로컬 기본값입니다:$DEFAULTED"
        echo "        APP_PASSWORD / MIGRATOR_PASSWORD / READONLY_PASSWORD / DB_ROOT_PASSWORD 를 지정하세요."
        exit 1
    fi
fi

# ---------------------------------------------------------------------
# 비밀번호를 명령줄에 노출하지 않는다 (mysql -p암호는 ps로 보인다)
# ---------------------------------------------------------------------
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

write_cnf() {  # $1=파일경로 $2=계정 $3=비밀번호
    cat > "$1" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$2
password=$3
EOF
    chmod 600 "$1"
}

ROOT_CNF="$TMP_DIR/root.cnf"
MIGRATOR_CNF="$TMP_DIR/migrator.cnf"
write_cnf "$ROOT_CNF"     "$DB_ROOT_USER"        "$DB_ROOT_PASSWORD"
write_cnf "$MIGRATOR_CNF" "$DB_MIGRATOR_USER"  "$MIGRATOR_PASSWORD"

# 헬퍼: migrator 계정으로 한 줄짜리 SQL 실행 (헤더 없이 값만)
mig_q() { mysql --defaults-extra-file="$MIGRATOR_CNF" -N -B "$DB_NAME" -e "$1"; }

# ---------------------------------------------------------------------
# time_zone 검증
#   DATETIME 컬럼(clicked_at 등)은 TIMESTAMP와 달리 UTC로 자동 변환되지
#   않고 DB 서버의 time_zone 설정을 그대로 따른다. @@global.time_zone이
#   UTC가 아니면 NOW()로 채워지는 값이 KST 등으로 밀려서 저장되고,
#   02-stats-queries.sql의 KST<->UTC 배치 집계 경계가 에러 없이 조용히
#   틀어진다. 그래서 마이그레이션 실행 전에 여기서 미리 확인한다.
# ---------------------------------------------------------------------
check_timezone() {
    local tz_global tz_session now_val utc_val
    # NULL(빈 문자열)이 아닌 값만 받도록 -N -B, 필드는 탭 구분
    IFS=$'\t' read -r tz_global tz_session now_val utc_val < <(
        mysql --defaults-extra-file="$ROOT_CNF" -N -B -e \
            "SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();"
    )

    echo "==> time_zone 검증"
    echo "    @@global.time_zone  = $tz_global"
    echo "    @@session.time_zone = $tz_session"
    echo "    NOW()                = $now_val"
    echo "    UTC_TIMESTAMP()      = $utc_val"

    if [ "$tz_global" = "+00:00" ] || [ "$tz_global" = "UTC" ]; then
        echo "    정상 (UTC)"
        return 0
    fi

    if [ "$ALLOW_NON_UTC" -eq 1 ]; then
        echo "[WARN] time_zone이 UTC가 아닙니다 (현재: $tz_global)."
        echo "        --allow-non-utc / ALLOW_NON_UTC=1 지정으로 경고만 하고 계속 진행합니다."
        echo "        DATETIME 컬럼(clicked_at 등)이 UTC가 아닌 값으로 저장되며,"
        echo "        02-stats-queries.sql의 KST<->UTC 집계 경계가 어긋날 수 있습니다."
        return 0
    fi

    echo "[ERROR] time_zone이 UTC가 아닙니다 (현재: $tz_global)."
    echo "        clicked_at 등 DATETIME 컬럼은 이 설정을 그대로 따르므로,"
    echo "        지금 상태로 진행하면 UTC로 저장한다는 설계 전제가 깨집니다."
    echo "        해결:"
    echo "          - 로컬 도커: docker-compose.yml에 command: --default-time-zone=+00:00 추가"
    echo "          - RDS: 파라미터 그룹에서 time_zone 파라미터를 UTC로 설정"
    echo "        의도적으로 다른 타임존을 쓰는 경우에만 --allow-non-utc 로 이 검증을 건너뛰세요."
    exit 1
}

# ---------------------------------------------------------------------
# --status: 이력만 보여주고 종료
# ---------------------------------------------------------------------
if [ "$STATUS_ONLY" -eq 1 ]; then
    mysql --defaults-extra-file="$ROOT_CNF" "$DB_NAME" -e \
        "SELECT version, script, success, installed_at
           FROM schema_history ORDER BY installed_rank;" 2>/dev/null \
        || echo "schema_history 테이블이 아직 없습니다. 마이그레이션을 먼저 실행하세요."
    exit 0
fi

# ---------------------------------------------------------------------
# MySQL 응답 대기 (도커를 방금 올린 경우 대비)
# ---------------------------------------------------------------------
echo "==> MySQL 접속 대기 ($DB_HOST:$DB_PORT)"
for i in $(seq 1 30); do
    if mysqladmin --defaults-extra-file="$ROOT_CNF" ping --silent >/dev/null 2>&1; then
        echo "    연결됨"; break
    fi
    [ "$i" -eq 30 ] && { echo "[ERROR] 30초 안에 접속하지 못했습니다."; exit 1; }
    sleep 1
done

check_timezone

# ---------------------------------------------------------------------
# 1) 00-database-setup.sql — DB / 계정 / 권한
#    <CHANGE_ME_...>를 치환한 사본을 임시 디렉터리에 만들어 실행한다.
#    원본은 건드리지 않으므로 비밀번호가 커밋될 일이 없다.
# ---------------------------------------------------------------------
if [ "$SKIP_SETUP" -eq 0 ]; then
    [ -f 00-database-setup.sql ] || { echo "[ERROR] 00-database-setup.sql 이 없습니다."; exit 1; }
    echo "==> 00-database-setup.sql (DB / 계정 / 권한)"
    # DB_NAME도 비밀번호와 동일하게 치환한다. 이전엔 00-database-setup.sql이
    # DB명을 'shortlink'로 하드코딩하고 있어서, DB_NAME을 shortlink 외의 값으로
    # 바꿔도 00 단계는 여전히 'shortlink' DB만 만들고 그 DB에만 권한을 부여했다.
    # 그 결과 뒤이은 V*.sql 적용 단계가 "$DB_NAME"으로 접속을 시도하다
    # Unknown database 에러로 실패하는 문제가 있었다(계정명은 DB_MIGRATOR_USER로
    # 이미 분리해뒀던 것과 같은 종류의 문제였는데 DB명 쪽은 놓쳐서 남아있었다).
    sed -e "s|<CHANGE_ME_APP_PASSWORD>|$APP_PASSWORD|" \
        -e "s|<CHANGE_ME_MIGRATOR_PASSWORD>|$MIGRATOR_PASSWORD|" \
        -e "s|<CHANGE_ME_READONLY_PASSWORD>|$READONLY_PASSWORD|" \
        -e "s|<CHANGE_ME_DB_NAME>|$DB_NAME|g" \
        00-database-setup.sql > "$TMP_DIR/00.sql"
    mysql --defaults-extra-file="$ROOT_CNF" < "$TMP_DIR/00.sql" > /dev/null
    echo "    완료"
fi

# ---------------------------------------------------------------------
# 2) 이력 테이블 준비
#    Flyway의 flyway_schema_history를 최소한으로 흉내낸다.
#    이름을 다르게 둔 이유: 나중에 실제 Flyway를 붙일 때 충돌하지 않도록.
# ---------------------------------------------------------------------
mig_q "
CREATE TABLE IF NOT EXISTS schema_history (
    installed_rank INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version        VARCHAR(20)  NOT NULL,
    script         VARCHAR(255) NOT NULL,
    checksum       CHAR(64)     NOT NULL COMMENT 'SHA-256. 적용 후 파일이 바뀌면 감지된다',
    success        BOOLEAN      NOT NULL,
    installed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_schema_history_version (version)
) COMMENT '마이그레이션 적용 이력';" > /dev/null

# ---------------------------------------------------------------------
# 3) V*.sql 순차 적용
# ---------------------------------------------------------------------
shopt -s nullglob
MIGRATIONS=(V*__*.sql)
shopt -u nullglob

[ ${#MIGRATIONS[@]} -eq 0 ] && { echo "[ERROR] V*__*.sql 파일이 없습니다."; exit 1; }

# V1, V2, ... V10 순서가 뒤집히지 않도록 버전 숫자로 정렬한다 (사전순이면 V10 < V2)
IFS=$'\n' MIGRATIONS=($(printf '%s\n' "${MIGRATIONS[@]}" | sort -t'V' -k2 -n)); unset IFS

FAILED=""
for script in "${MIGRATIONS[@]}"; do
    version="${script%%__*}"                          # V1__init_schema.sql -> V1
    checksum="$(sha256sum "$script" | cut -d' ' -f1)"

    prev="$(mig_q "SELECT CONCAT(success, ':', checksum) FROM schema_history
                    WHERE version = '$version' LIMIT 1;" || true)"

    if [ -n "$prev" ]; then
        prev_success="${prev%%:*}"
        prev_checksum="${prev##*:}"

        if [ "$prev_checksum" != "$checksum" ]; then
            echo "[ERROR] $script 이(가) 적용 후 수정됐습니다 (체크섬 불일치)."
            echo "        이미 적용된 마이그레이션은 고치지 말고 새 버전(V$(( ${version#V} + 1 )))을 추가하세요."
            exit 1
        fi
        if [ "$prev_success" = "1" ]; then
            echo "==> $script  [건너뜀 - 이미 적용됨]"
            continue
        fi
        echo "==> $script  [재시도 - 이전 실행 실패]"
        mig_q "DELETE FROM schema_history WHERE version = '$version';" > /dev/null
    else
        echo "==> $script"
    fi

    if mysql --defaults-extra-file="$MIGRATOR_CNF" "$DB_NAME" < "$script"; then
        mig_q "INSERT INTO schema_history (version, script, checksum, success)
               VALUES ('$version', '$script', '$checksum', TRUE);" > /dev/null
        echo "    성공"
    else
        mig_q "INSERT INTO schema_history (version, script, checksum, success)
               VALUES ('$version', '$script', '$checksum', FALSE);" > /dev/null
        echo "    [실패] 위 에러 메시지를 확인하세요."
        FAILED="$FAILED $version"
        # 여기서 멈추지 않고 계속 진행한다.
        # 한 버전이 실패했다고 이후 마이그레이션까지 막으면
        # 무엇이 되고 무엇이 안 되는지 한 번에 파악할 수 없기 때문이다.
    fi
done

# ---------------------------------------------------------------------
# 4) 검증
# ---------------------------------------------------------------------
echo
echo "==> 적용 이력"
mysql --defaults-extra-file="$ROOT_CNF" "$DB_NAME" -e \
    "SELECT version, script, success, installed_at
       FROM schema_history ORDER BY installed_rank;"

echo "==> 스키마 상태"
mysql --defaults-extra-file="$ROOT_CNF" "$DB_NAME" <<'SQL'
SELECT table_name, table_comment
  FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name <> 'schema_history'
 ORDER BY table_name;
SQL

echo
if [ -z "$FAILED" ]; then
    echo "완료. 모든 마이그레이션이 적용됐습니다. (테이블 5개)"
else
    echo "일부 실패:$FAILED"
    echo "  위 에러 메시지와 README.md의 '알려진 제약사항'을 확인하세요."
    echo "  이미 적용된 파일은 수정하지 말고 새 버전(V2, V3 ...)을 추가해야 합니다."
    exit 1
fi
echo "02-stats-queries.sql은 조회/집계 쿼리 모음이라 실행하지 않았습니다."
