#!/usr/bin/env bash
# "Warmup 없는 바이럴 링크" 시나리오 원스톱 실행: 콜드 링크 DB 직접 INSERT →
# viral-spike.js 실행 → 테스트 링크 soft delete. load-test/SCENARIOS.md 참고.
#
# management-service API 대신 MySQL에 직접 INSERT하는 이유: API로 만들면 생성 즉시
# 캐시가 채워지고(RedirectCacheRefreshEventListener), slug도 자동생성이라 지정 불가,
# 카카오 로그인도 필요해서 자동화하기 번거로움.
#
# 사용법:
#   load-test/viral-spike-run.sh <kubectl-context> [base-url] [num-slugs] [spike-tps]
#
# 예시:
#   load-test/viral-spike-run.sh snipy                    # 기본값: 내부 Service DNS
#   load-test/viral-spike-run.sh snipy http://redirect-service:8080 3
#   load-test/viral-spike-run.sh snipy http://redirect-service:8080 3 800   # 목표 TPS 지정
#
# spike-tps를 비우면 viral-spike.js의 기본값(SPIKE_TPS 1600)을 쓴다.
#
# base-url 기본값이 공개 도메인이 아니라 내부 DNS인 이유는 load-test/README.md 참고
# (WAF geo-match 때문에 클러스터 안에서 공개 도메인 치면 403).
#
# 전제: kubectl context가 MFA 인증된 상태, aws secretsmanager 읽기 권한.
set -euo pipefail

CONTEXT="${1:?사용법: viral-spike-run.sh <kubectl-context> [base-url] [num-slugs] [spike-tps]}"
BASE_URL="${2:-http://redirect-service:8080}"
NUM_SLUGS="${3:-3}"
# 비우면 run-smoke.sh·템플릿을 거쳐 빈 값이 넘어가고 viral-spike.js가 기본값을 쓴다
export SPIKE_TPS="${4:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# dev/prod 클러스터 이름 매핑 (Secrets Manager 키 prefix). 새 환경이 생기면 여기만 추가.
case "$CONTEXT" in
  snipy)      CLUSTER_NAME="snipy-dev-cluster" ;;
  snipy-prod) CLUSTER_NAME="snipy-cluster" ;;
  *) echo "알 수 없는 context: $CONTEXT (CLUSTER_NAME 매핑을 스크립트에 추가하세요)" >&2; exit 1 ;;
esac

echo ">> [$CONTEXT] DB 접속 정보 조회"
DB_HOST=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_HOST}')
DB_PORT=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_PORT}')
DB_NAME=$(kubectl --context "$CONTEXT" get configmap redirect-service-config -o jsonpath='{.data.DB_NAME}')
DB_PASSWORD=$(aws secretsmanager get-secret-value \
  --region ap-southeast-1 \
  --secret-id "${CLUSTER_NAME}/rds/app-user-password" \
  --query SecretString --output text)

# kubectl run으로 mysql 클라이언트 파드를 띄워 1회성 SQL 실행 (-N -B로 순수 출력만).
# --rm -i(attach)는 실패 시 로그가 중복 캡처되는 문제가 있어서, 파드 생성 후 종료
# 상태(phase)를 폴링하고 kubectl logs로 한 번만 읽는 방식을 쓴다.
run_sql() {
  local sql="$1"
  local pod_name="mysql-client-$$-${RANDOM}"

  kubectl --context "$CONTEXT" run "$pod_name" --restart=Never --image=mysql:8 --quiet \
    --env="MYSQL_PWD=${DB_PASSWORD}" \
    --command -- mysql -N -B -h "$DB_HOST" -P "$DB_PORT" -u shortlink_app "$DB_NAME" -e "$sql" \
    >/dev/null

  local phase=""
  for _ in $(seq 1 60); do
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
# 탈퇴한 회원(#202)은 개인 워크스페이스가 DELETED라 활성 회원만 고른다
USER_ID=$(run_sql "SELECT id FROM users WHERE status = 'ACTIVE' ORDER BY id LIMIT 1;" | tr -d '\r' | head -n1)
if [[ -z "$USER_ID" ]]; then
  echo "활성 회원이 없음 — 소셜 로그인으로 가입한 계정이 하나 이상 있어야 함" >&2
  exit 1
fi

# 링크는 워크스페이스 소유다(links.workspace_id NOT NULL, #206). 그 사용자의 개인 워크스페이스에 만든다
WORKSPACE_ID=$(run_sql "SELECT id FROM workspaces WHERE personal_owner_id = ${USER_ID} AND status = 'ACTIVE';" | tr -d '\r' | head -n1)
if [[ -z "$WORKSPACE_ID" ]]; then
  echo "!! user_id=${USER_ID}의 개인 워크스페이스가 없음 — 마이그레이션(V10~V12)이 적용됐는지 확인하세요." >&2
  exit 1
fi

echo "   user_id=$USER_ID"

TS=$(date +%s)
# slug는 VARCHAR(20) 제약 — 길면 RDS가 조용히 잘라서 UNIQUE 충돌 남(실제로 겪음).
# "vs" + epoch 뒤 8자리 + index로 짧게 유지.
SLUGS=()
for i in $(seq 1 "$NUM_SLUGS"); do
  SLUGS+=("vs${TS: -8}${i}")
done
SLUG_CSV=$(IFS=,; echo "${SLUGS[*]}")

echo ">> [$CONTEXT] 콜드 링크 ${NUM_SLUGS}개 INSERT ($SLUG_CSV)"
INSERT_SQL="INSERT INTO links (link_id, user_id, workspace_id, slug, original_url, is_visible, expires_at) VALUES "
VALUES=()
for slug in "${SLUGS[@]}"; do
  VALUES+=("(UUID(), ${USER_ID}, ${WORKSPACE_ID}, '${slug}', 'https://example.com/viral-spike-test', TRUE, NULL)")
done
INSERT_SQL+=$(IFS=,; echo "${VALUES[*]}")
INSERT_SQL+=";"
run_sql "$INSERT_SQL"

# SQL 로 바로 바꿔서 리다이렉트 캐시(Redis)는 지우지 않는다. 캐시에 남은 slug 는 TTL(기본 10분) 동안 계속 리다이렉트된다
cleanup() {
  echo ">> [$CONTEXT] 테스트 링크 정리 (slug IN $SLUG_CSV)"
  local in_clause
  in_clause=$(printf "'%s'," "${SLUGS[@]}")
  in_clause="${in_clause%,}"
  # 앱의 링크 삭제와 같은 soft delete. 실제로 지우면 S3에 남은 클릭이 집계 외래키(1452)에 걸린다 (#251)
  # (#251)실제로 지우면 S3에 남은 클릭이 집계 외래키(1452)에 걸린다 (#251)
  run_sql "UPDATE links SET is_visible = FALSE WHERE slug IN (${in_clause});" || \
    echo "!! 자동 정리 실패 — 수동 정리 필요: UPDATE links SET is_visible = FALSE WHERE slug IN (${in_clause});" >&2
}
trap cleanup EXIT

echo ">> [$CONTEXT] k6 viral-spike 실행 (SPIKE_TPS=${SPIKE_TPS:-기본값 1600})"
"$SCRIPT_DIR/run-smoke.sh" "$CONTEXT" viral-spike.js "$BASE_URL" "$SLUG_CSV"
