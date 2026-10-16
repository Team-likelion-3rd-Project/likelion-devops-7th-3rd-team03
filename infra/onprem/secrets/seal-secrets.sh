#!/usr/bin/env bash
# 시크릿 값으로 SealedSecret 파일(<이름>.sealed.yaml)을 만든다. 결과 파일은 Git에 커밋해도 된다.
#   cp secrets.example.env secrets.env      # 값을 채운다. secrets.env 는 .gitignore 대상이다.
#   bash infra/onprem/secrets/seal-secrets.sh
# 값은 환경 변수가 있으면 그걸 쓰고, 없으면 secrets.env 에서 읽는다.
# 필요한 것: kubectl, kubeseal, 공개 인증서(sealed-secrets-pub.pem). 클러스터에는 접속하지 않는다.
set -euo pipefail
cd "$(dirname "$0")"

NS="${NAMESPACE:-snipy}"
CERT="${CERT:-sealed-secrets-pub.pem}"
VALUES_FILE="${VALUES_FILE:-secrets.env}"

[ -f "$CERT" ] || { echo "!! $CERT 가 없습니다. README 의 '3. 공개 인증서 받기'를 먼저 하세요." >&2; exit 1; }
command -v kubeseal >/dev/null || { echo "!! kubeseal 이 없습니다. README 의 '4. kubeseal 설치'를 참고하세요." >&2; exit 1; }
command -v kubectl >/dev/null || { echo "!! kubectl 이 없습니다." >&2; exit 1; }

# 환경 변수 -> secrets.env 순서로 값을 찾는다. secrets.env 는 따옴표 없이 KEY=값 으로 쓴다.
value() {
  local k="$1" v="${!1:-}"
  if [ -z "$v" ] && [ -f "$VALUES_FILE" ]; then
    v="$(grep "^$k=" "$VALUES_FILE" | head -1 | cut -d= -f2- | tr -d '\r' || true)"
  fi
  printf '%s' "$v"
}

TMP="$(mktemp)"
chmod 600 "$TMP"
trap 'rm -f "$TMP"' EXIT

# seal <Secret 이름> <필수 키...> [-- <선택 키...>]
# 필수 키가 비어 있으면 멈춘다. 선택 키는 비어 있어도 빈 값으로 넣는다.
seal() {
  local name="$1"; shift
  local required=() optional=() mode=required k v
  for k in "$@"; do
    if [ "$k" = "--" ]; then mode=optional; continue; fi
    if [ "$mode" = required ]; then required+=("$k"); else optional+=("$k"); fi
  done

  : > "$TMP"
  for k in "${required[@]}"; do
    v="$(value "$k")"
    [ -n "$v" ] || { echo "!! $name: $k 값이 비어 있습니다" >&2; exit 1; }
    printf '%s=%s\n' "$k" "$v" >> "$TMP"
  done
  for k in "${optional[@]+"${optional[@]}"}"; do
    printf '%s=%s\n' "$k" "$(value "$k")" >> "$TMP"
  done

  kubectl create secret generic "$name" -n "$NS" --from-env-file="$TMP" --dry-run=client -o json \
    | kubeseal --cert "$CERT" --scope strict --format yaml > "$name.sealed.yaml"
  echo "   $name.sealed.yaml"
}

echo ">> SealedSecret 만들기 (네임스페이스 $NS)"
seal mysql-secrets MYSQL_ROOT_PASSWORD DB_APP_PASSWORD DB_MIGRATOR_PASSWORD READONLY_PASSWORD
seal management-service-secrets DB_APP_PASSWORD JWT_SECRET KAKAO_CLIENT_ID -- KAKAO_CLIENT_SECRET GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
seal redirect-service-secrets DB_APP_PASSWORD JWT_SECRET
# SeaweedFS S3 키는 #20에서 정하는 Secret 이름에 맞춰 추가한다.
#   seal seaweedfs-s3-secrets S3_ACCESS_KEY S3_SECRET_KEY

echo "완료. 만들어진 파일을 커밋하고, 클러스터에는 kubectl apply -f <파일> 로 적용한다."
