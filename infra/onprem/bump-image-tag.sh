#!/usr/bin/env bash
# overlays/dev 의 이미지 태그를 바꾼다. CI 가 이미지를 GHCR 에 올린 뒤 부른다.
#   bash infra/onprem/bump-image-tag.sh <서비스...> <태그>
#   bash infra/onprem/bump-image-tag.sh frontend "$GITHUB_SHA"
#   bash infra/onprem/bump-image-tag.sh management-service redirect-service db-migration "$GITHUB_SHA"
# 서비스는 images 의 newName 이 "-<서비스>" 로 끝나는 항목으로 찾는다 (frontend, management-service, ...).
# 대상 파일을 바꾸려면 FILE 환경 변수를 쓴다. 서비스를 하나라도 못 찾으면 파일을 건드리지 않고 실패한다.
set -euo pipefail
cd "$(dirname "$0")/../.."

FILE="${FILE:-infra/k8s/overlays/dev/kustomization.yaml}"
[ "$#" -ge 2 ] || { echo "사용법: $0 <서비스...> <태그>" >&2; exit 1; }
TAG="${!#}"
set -- "${@:1:$#-1}"
[[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "!! 태그 형식이 이상합니다: $TAG" >&2; exit 1; }

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# newName 줄이 서비스 이름으로 끝나면 바로 다음 newTag 줄을 바꾼다. \r? 는 CRLF 로 체크아웃된 파일용이다.
awk -v tag="$TAG" -v svcs="$*" '
  BEGIN { n = split(svcs, a, " ") }
  /newName:/ { cur = ""; for (i = 1; i <= n; i++) if ($0 ~ ("-" a[i] "\r?$")) cur = a[i] }
  cur != "" && /newTag:/ { sub(/newTag:[^\r]*/, "newTag: " tag); done[cur] = 1; cur = "" }
  { print }
  END {
    for (i = 1; i <= n; i++) if (!done[a[i]]) { print "!! 못 찾음: " a[i] > "/dev/stderr"; bad = 1 }
    exit bad
  }
' "$FILE" > "$TMP"

cat "$TMP" > "$FILE"
echo ">> $FILE: $* -> $TAG"
