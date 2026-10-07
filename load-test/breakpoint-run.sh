#!/usr/bin/env bash
# ==============================================================================
# 단일 파드 처리량 한계(breakpoint) 측정용 원스톱 실행 스크립트
#
# [동작 순서]
# 1. redirect-service 파드 하나를 골라 그 Pod IP로 직접 부하를 준다 (Service를
#    거치면 여러 파드로 로드밸런싱되어 "파드 1개 한계"를 잴 수 없음. HPA/Deployment를
#    직접 건드리면 ArgoCD selfHeal이 되돌리므로, 대신 파드 하나를 Pod IP로 격리한다.)
# 2. MySQL로 작은 링크 풀 생성 (기본 20개 — 캐시 미스/DB 부하가 아니라 캐시 히트
#    경로에서 파드가 순수하게 얼마나 처리하는지를 보기 위함)
# 3. k6 breakpoint.js 실행 (TPS를 계속 올리다 에러율/latency 임계치를 넘으면 자동 중단)
# 4. 종료/중단 시 trap으로 생성된 링크 자동 정리
#
# [사용법]
#   load-test/breakpoint-run.sh <kubectl-context> [num-links] [max-tps] [ramp-duration]
#
# [예시]
#   load-test/breakpoint-run.sh snipy                    # 기본: 링크 20개, 2000 TPS까지 10분에 걸쳐 램프
#   load-test/breakpoint-run.sh snipy 20 5000 20m         # 더 높은 상한/느린 램프로 정밀 측정
# ==============================================================================

set -euo pipefail

CONTEXT="${1:?사용법: breakpoint-run.sh <kubectl-context> [num-links] [max-tps] [ramp-duration]}"
NUM_LINKS="${2:-20}"
MAX_TPS="${3:-2000}"
RAMP_DURATION="${4:-10m}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Context별 AWS Secrets Manager 클러스터 경로 매핑
case "$CONTEXT" in
  snipy)      CLUSTER_NAME="snipy-dev-cluster" ;;
  snipy-prod) CLUSTER_NAME="snipy-cluster" ;;
  *) echo "!! 알 수 없는 context: $CONTEXT (CLUSTER_NAME 매핑을 스크립트에 추가하세요)" >&2; exit 1 ;;
esac

echo ">> [$CONTEXT] redirect-service 파드 선택 (Service 안 거치고 Pod IP로 직접 부하 — 다른 파드로 분산 방지)"
POD_NAME=$(kubectl --context "$CONTEXT" get pods -l app=redirect-service \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
if [[ -z "$POD_NAME" ]]; then
  echo "!! 실행 중인 redirect-service 파드를 찾을 수 없음" >&2
  exit 1
fi
POD_IP=$(kubectl --context "$CONTEXT" get pod "$POD_NAME" -o jsonpath='{.status.podIP}')
BASE_URL="http://${POD_IP}:8080"
echo "   대상 파드: $POD_NAME ($BASE_URL)"
echo "   !! 테스트 도중 이 파드가 재시작/재스케줄되면 IP가 바뀌어 결과가 무효화됩니다."
echo "      (kubectl get pods -l app=redirect-service --context $CONTEXT 로 사전에 안정적인지 확인 권장)"

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
  for _ in $(seq 1 120); do
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

TS=$(date +%s)
SLUG_PREFIX="bp${TS: -6}"

# 스크립트 비정상 종료(Ctrl+C, Error 등) 시에도 테스트 링크를 반드시 삭제하도록 trap 설정
cleanup() {
  echo ">> [$CONTEXT] 테스트 링크 정리 (prefix=${SLUG_PREFIX}*)"
  run_sql "DELETE FROM links WHERE slug LIKE '${SLUG_PREFIX}%';" || \
    echo "!! 자동 정리 실패 — 수동 삭제 필요: DELETE FROM links WHERE slug LIKE '${SLUG_PREFIX}%';" >&2
}
trap cleanup EXIT

echo ">> [$CONTEXT] 링크 ${NUM_LINKS}개 (prefix=$SLUG_PREFIX) INSERT"
run_sql "
  SET SESSION cte_max_recursion_depth = $((NUM_LINKS + 10));
  INSERT INTO links (link_id, user_id, slug, original_url, is_visible, expires_at)
  WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < ${NUM_LINKS}
  )
  SELECT UUID(), ${USER_ID}, CONCAT('${SLUG_PREFIX}', n), 'https://example.com/breakpoint-test', TRUE, NULL
  FROM seq;
"

echo ">> [$CONTEXT] k6 breakpoint 실행 (파드=$POD_NAME, 링크 ${NUM_LINKS}개, MAX_TPS=${MAX_TPS}, RAMP_DURATION=${RAMP_DURATION})"
export SLUG_PREFIX
export SLUG_COUNT="$NUM_LINKS"
export MAX_TPS
export RAMP_DURATION
"$SCRIPT_DIR/run-smoke.sh" "$CONTEXT" breakpoint.js "$BASE_URL"
