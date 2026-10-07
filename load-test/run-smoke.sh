#!/usr/bin/env bash
# k6 스크립트를 EKS 클러스터 안에서 1회 실행하는 공용 러너 (CronJob 아님).
# viral-spike-run.sh/normal-traffic-run.sh가 내부적으로 이걸 호출한다.
#
# 사용법:
#   load-test/run-smoke.sh <kubectl-context> <script-file> <base-url> [slug] [vus] [duration]
#
# 예시:
#   load-test/run-smoke.sh snipy redirect-smoke.js https://dev.snipy.life test 10 30s
#
# vus/duration은 redirect-smoke.js 전용. SLUG_PREFIX/SLUG_COUNT/PEAK_TPS/MAX_TPS/RAMP_DURATION은
# 호출 전에 export해두면 그대로 Job에 전달됨 (normal-traffic-run.sh, breakpoint-run.sh 참고).
set -euo pipefail

CONTEXT="${1:?사용법: run-smoke.sh <kubectl-context> <script-file> <base-url> [slug] [vus] [duration]}"
SCRIPT_FILE="${2:?스크립트 파일명 지정 (예: redirect-smoke.js, viral-spike.js)}"
BASE_URL="${3:?base-url 지정 (예: https://dev.snipy.life)}"
SLUG="${4:-}"
VUS="${5:-10}"
DURATION="${6:-30s}"
SLUG_PREFIX="${SLUG_PREFIX:-}"
SLUG_COUNT="${SLUG_COUNT:-0}"
PEAK_TPS="${PEAK_TPS:-}"
MAX_TPS="${MAX_TPS:-}"
RAMP_DURATION="${RAMP_DURATION:-}"
# SLUG_PREFIX가 있으면(*-run.sh가 이미 유니크하게 생성해둔 값) 그대로 재사용하고,
# 없으면(redirect-smoke.js 단독 실행 등) 여기서 새로 하나 만든다.
TESTID="${TESTID:-${SLUG_PREFIX:-$(date +%s)-${RANDOM}}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$SCRIPT_DIR/$SCRIPT_FILE" ]]; then
  echo "스크립트 파일을 찾을 수 없음: $SCRIPT_DIR/$SCRIPT_FILE" >&2
  exit 1
fi

echo ">> [$CONTEXT] 이전 Job/ConfigMap 정리"
kubectl --context "$CONTEXT" delete job k6-load-test --ignore-not-found
kubectl --context "$CONTEXT" delete configmap k6-load-test-script --ignore-not-found

echo ">> [$CONTEXT] 테스트 스크립트 ConfigMap 생성 ($SCRIPT_FILE)"
kubectl --context "$CONTEXT" create configmap k6-load-test-script \
  --from-file="$SCRIPT_FILE=$SCRIPT_DIR/$SCRIPT_FILE"

echo ">> TESTID=$TESTID (Grafana/Prometheus/Athena에서 testid=\"$TESTID\"로 이번 실행만 필터링 가능)"

echo ">> [$CONTEXT] Job 생성 (SCRIPT=$SCRIPT_FILE BASE_URL=$BASE_URL SLUG=$SLUG VUS=$VUS DURATION=$DURATION)"
SCRIPT_FILE="$SCRIPT_FILE" BASE_URL="$BASE_URL" SLUG="$SLUG" VUS="$VUS" DURATION="$DURATION" \
  SLUG_PREFIX="$SLUG_PREFIX" SLUG_COUNT="$SLUG_COUNT" PEAK_TPS="$PEAK_TPS" \
  MAX_TPS="$MAX_TPS" RAMP_DURATION="$RAMP_DURATION" TESTID="$TESTID" \
  envsubst < "$SCRIPT_DIR/job.yaml.template" | kubectl --context "$CONTEXT" apply -f -

echo ">> Job 완료 대기 (최대 25분)"
kubectl --context "$CONTEXT" wait --for=condition=complete --timeout=25m job/k6-load-test || true

echo ">> 결과 로그"
kubectl --context "$CONTEXT" logs job/k6-load-test

cat <<EOF

정리하려면:
  kubectl --context $CONTEXT delete job k6-load-test configmap k6-load-test-script

이번 테스트로 생성된 더미 데이터(slug=$SLUG)는 별도로 DB/S3에서 정리해야 함.
EOF
