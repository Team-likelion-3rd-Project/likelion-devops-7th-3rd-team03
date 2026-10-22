#!/usr/bin/env bash
# 이미지 취약점 스캔. 레지스트리에 push 하기 전에 호출한다.
#   bash .github/scripts/trivy-scan.sh <이미지>
#
# CRITICAL 이고 패치가 나온 취약점이 있으면 실패한다 (push 단계가 실행되지 않는다).
# 패치가 없는 취약점(--ignore-unfixed)은 우리가 고칠 방법이 없어서 막지 않는다.
#
# 막혔을 때:
#   1. 표의 FixedVersion 으로 의존성을 올린다 (Spring Boot 패치 → 안 되면 build.gradle 의 ext 로 개별 지정)
#   2. 당장 못 올리면 저장소 루트 .trivyignore 에 CVE ID 와 사유·재검토일을 적는다
#
# 보고만 하고 막지 않으려면 TRIVY_EXIT_CODE=0
set -euo pipefail

IMAGE="${1:?스캔할 이미지가 필요합니다}"
TRIVY_VERSION="${TRIVY_VERSION:-0.56.2}"
ROOT="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"

ignore_args=()
if [ -f "$ROOT/.trivyignore" ]; then
  ignore_args=(-v "$ROOT/.trivyignore:/trivyignore:ro" --ignorefile /trivyignore)
  # --ignorefile 은 trivy 옵션이라 이미지 이름 뒤가 아니라 앞에 둬야 한다
fi

mkdir -p "$HOME/.cache/trivy"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$HOME/.cache/trivy:/root/.cache/trivy" \
  ${ignore_args[@]:0:2} \
  "aquasec/trivy:${TRIVY_VERSION}" image \
  --scanners vuln \
  --severity CRITICAL \
  --ignore-unfixed \
  --exit-code "${TRIVY_EXIT_CODE:-1}" \
  --no-progress \
  --db-repository public.ecr.aws/aquasecurity/trivy-db \
  --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db \
  ${ignore_args[@]:2} \
  "$IMAGE"