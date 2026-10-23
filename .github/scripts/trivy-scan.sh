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
# 2026-03 공급망 공격으로 0.69.4~0.69.6 이미지가 악성으로 배포됐다 (CVE-2026-33634).
# 태그는 덮어쓸 수 있어서, 안전 버전으로 명시된 0.69.3을 digest로 고정한다.
# 올릴 때는 Docker Hub와 ghcr.io/aquasecurity/trivy의 digest가 같은지 확인하고 바꾼다.
TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.69.3@sha256:bcc376de8d77cfe086a917230e818dc9f8528e3c852f7b1aff648949b6258d1c}"
ROOT="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"

# 취약점 DB 저장소. 공개 저장소는 GitHub Actions 러너처럼 IP를 공유하는 환경에서
# 요청 제한(TOOMANYREQUESTS)에 자주 걸려, 막히면 다음 저장소로 넘어간다.
DB_REPOS=(mirror.gcr.io/aquasec/trivy-db ghcr.io/aquasecurity/trivy-db public.ecr.aws/aquasecurity/trivy-db)
JAVA_DB_REPOS=(mirror.gcr.io/aquasec/trivy-java-db ghcr.io/aquasecurity/trivy-java-db public.ecr.aws/aquasecurity/trivy-java-db)

mkdir -p "$HOME/.cache/trivy"

docker_args=(
  --rm
  -v /var/run/docker.sock:/var/run/docker.sock
  -v "$HOME/.cache/trivy:/root/.cache/trivy"
)
if [ -f "$ROOT/.trivyignore" ]; then
  docker_args+=(-v "$ROOT/.trivyignore:/trivyignore:ro")
fi

trivy() {
  docker run "${docker_args[@]}" "$TRIVY_IMAGE" "$@"
}

# 저장소를 순서대로, 저장소마다 최대 3번 시도한다.
# 캐시에 최신 DB가 있으면 실제로 받지 않고 바로 성공한다.
download() {
  local what="$1" flag="$2"; shift 2
  for repo in "$@"; do
    for attempt in 1 2 3; do
      if trivy image "--download-${what}-only" "$flag" "$repo" --no-progress; then
        return 0
      fi
      echo "!! $what 다운로드 실패: $repo (시도 $attempt/3)" >&2
      sleep $(( attempt * 10 ))
    done
  done
  echo "!! 모든 저장소에서 $what 다운로드에 실패했습니다" >&2
  return 1
}

download db      --db-repository      "${DB_REPOS[@]}"
download java-db --java-db-repository "${JAVA_DB_REPOS[@]}"

scan_args=(
  image
  --scanners vuln
  --severity CRITICAL
  --ignore-unfixed
  --exit-code "${TRIVY_EXIT_CODE:-1}"
  --no-progress
  # 위에서 받은 DB만 쓴다 (스캔 중에 다시 받으러 가지 않는다)
  --skip-db-update
  --skip-java-db-update
  # 이미지 분석 결과는 디스크에 남기지 않는다 (캐시가 스캔할수록 커지는 것을 막는다)
  --cache-backend memory
)
if [ -f "$ROOT/.trivyignore" ]; then
  scan_args+=(--ignorefile /trivyignore)
fi

trivy "${scan_args[@]}" "$IMAGE"