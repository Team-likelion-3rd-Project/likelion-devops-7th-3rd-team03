#!/usr/bin/env bash
# ==============================================================================
# Load/Stress 겸용 원스톱 실행 스크립트
#
# [동작 순서]
# 1. MySQL 재귀 CTE를 통해 링크 대량 생성 (기본 30만 개)
# 2. k6 normal-traffic.js 실행 (2분 상승 → 6분 유지 → 2분 하강, 총 10분)
# 3. 테스트 완료 또는 중단(Ctrl+C, 에러) 시 trap을 통해 생성된 링크 자동 정리(DELETE)
#
# [사용법]
#   load-test/normal-traffic-run.sh <kubectl-context> [base-url] [num-links] [peak-tps]
#
# [예시]
#   load-test/normal-traffic-run.sh snipy                                          # Load: 기본 30만개, 230 TPS
#   load-test/normal-traffic-run.sh snipy http://redirect-service:8080 300000 23   # Load (낮은 TPS)
#   load-test/normal-traffic-run.sh snipy http://redirect-service:8080 300000 230  # Stress
# ==============================================================================

set -euo pipefail

CONTEXT="${1:?사용법: normal-traffic-run.sh <kubectl-context> [base-url] [num-links] [peak-tps]}"
BASE_URL="${2:-http://redirect-service:8080}"
NUM_LINKS="${3:-300000}"
PEAK_TPS="${4:-230}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Context별 AWS Secrets Manager 클러스터 경로 매핑
case "$CONTEXT" in
  snipy)      CLUSTER_NAME="snipy-dev-cluster" ;;
  snipy-prod) CLUSTER_NAME="snipy-cluster" ;;
  *) echo "!! 알 수 없는 context: $CONTEXT (CLUSTER_NAME 매핑을 스크립트에 추가하세요)" >&2; exit 1 ;;
esac

echo ">> [$CONTEXT] DB 접속 정보 조회"
DB_HOST=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_HOST}')
DB_PORT=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_PORT}')
DB_NAME=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_NAME}')
DB_PASSWORD=$(aws secretsmanager get-secret-value \
  --region ap-southeast-1 \
  --secret-id "${CLUSTER_NAME}/rds/app-user-password" \
  --query SecretString --output text)

# 통신 단절(Broken Pipe) 방지를 위해 임시 Pod 생성 후 비동기 폴링 방식으로 SQL 실행
run_sql() {
  local sql="$1"
  local pod_name="mysql-client-$$-${RANDOM}"

  kubectl --context "$CONTEXT" run "$pod_name" --restart=Never --image=mysql:8 --quiet \
    --env="MYSQL_PWD=${DB_PASSWORD}" \
    --command -- mysql -N -B -h "$DB_HOST" -P "$DB_PORT" -u shortlink_app "$DB_NAME" -e "$sql" \
    >/dev/null

  local phase=""
  for _ in $(seq 1 600); do # 대량 INSERT/DELETE 소요 시간을 고려하여 최대 10분 대기
    phase=$(kubectl --context "$CONTEXT" get pod "$pod_name" -o jsonpath='{.status.phase}' 2>/dev/null || echo "")
    [[ "$phase" == "Succeeded" || "$phase" == "Failed" ]] && break
    sleep 1
  done

  local output
  output=$(kubectl --context "$CONTEXT" logs "$pod_name" 2>&1)
  kubectl --context "$CONTEXT" delete pod "$pod_name" --ignore-not-found >/dev/null 2>&1

  if [[ "$phase" != "Succeeded" ]]; then
    echo "$output" >&2
    return 1
  fi
  echo "$output"
}

echo ">> [$CONTEXT] 기존 user id 조회"
USER_ID=$(run_sql "SELECT id FROM users ORDER BY id LIMIT 1;" | tr -d '\r' | head -n1)
if [[ -z "$USER_ID" ]]; then
  echo "!! users 테이블이 비어있음 — 카카오 로그인을 한 번이라도 한 계정이 있어야 합니다." >&2
  exit 1
fi
echo "   user_id=$USER_ID"

TS=$(date +%s)
SLUG_PREFIX="lt${TS: -6}"

# 스크립트 비정상 종료(Ctrl+C, Error 등) 시에도 테스트 링크를 반드시 삭제하도록 trap 설정
cleanup() {
  echo ">> [$CONTEXT] 테스트 링크 정리 (prefix=${SLUG_PREFIX}*)"
  run_sql "DELETE FROM links WHERE slug LIKE '${SLUG_PREFIX}%';" || \
    echo "!! 자동 정리 실패 — 수동 삭제 필요: DELETE FROM links WHERE slug LIKE '${SLUG_PREFIX}%';" >&2
}
trap cleanup EXIT

# 재귀 CTE로 서버 사이드에서 N개의 링크를 한 번에 생성 (네트워크 I/O 및 파싱 오버헤드 최소화)
echo ">> [$CONTEXT] 링크 ${NUM_LINKS}개 (prefix=$SLUG_PREFIX) INSERT"
run_sql "
  SET SESSION cte_max_recursion_depth = $((NUM_LINKS + 10));
  INSERT INTO links (link_id, user_id, slug, original_url, is_visible, expires_at)
  WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < ${NUM_LINKS}
  )
  SELECT UUID(), ${USER_ID}, CONCAT('${SLUG_PREFIX}', n), 'https://example.com/normal-traffic-test', TRUE, NULL
  FROM seq;
"

echo ">> [$CONTEXT] k6 normal-traffic 실행 (링크 ${NUM_LINKS}개, PEAK_TPS=${PEAK_TPS}, 총 10분)"
export SLUG_PREFIX
export SLUG_COUNT="$NUM_LINKS"
export PEAK_TPS
"$SCRIPT_DIR/run-smoke.sh" "$CONTEXT" normal-traffic.js "$BASE_URL"