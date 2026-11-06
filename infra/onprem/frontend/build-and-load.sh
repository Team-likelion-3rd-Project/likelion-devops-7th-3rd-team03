#!/usr/bin/env bash
# 프론트 운영 이미지를 빌드해서 워커에 넣는다. 레지스트리가 없어서 tar로 옮긴다.
#   bash infra/onprem/frontend/build-and-load.sh   (호스트 PC의 Git Bash)
# 환경 변수: IMAGE, BASE_URL, WORKERS, SSH_USER, SOCIAL_PROVIDERS, VITE_KAKAO_CLIENT_ID, VITE_GOOGLE_CLIENT_ID
#
# 기본값은 팀 표준인 localhost 터널(BASE_URL=http://localhost:3000)이다. 구글이 IP·HTTP redirect URI를 받지 않고,
# dev ConfigMap 의 redirect URI 도 이 주소라서 같은 값으로 빌드해야 로그인이 된다. 값이 번들에 들어가니 주소를 바꾸면 다시 빌드한다.
# 이 스크립트는 카카오·구글만 지원한다 (네이버 클라이언트 ID 는 아직 읽지 않는다. CI 이미지는 3종).
#   접속은 ssh -N -L 3000:192.168.122.21:30080 k8s@192.168.122.21 을 연 뒤 http://localhost:3000
# 워커마다 VM 사용자 비밀번호를 묻는다 (scp, ssh, sudo).
#
# GHCR 로 올리기 (tar 로 워커에 넣는 대신): 먼저 docker login ghcr.io 를 한 뒤 PUSH=1 로 실행한다.
#   PUSH=1 IMAGE=ghcr.io/team-likelion-3rd-project/likelion-devops-7th-3rd-team03-frontend:manual-1 bash infra/onprem/frontend/build-and-load.sh
# 카카오 ID 는 저장소 루트의 .env(KAKAO_CLIENT_ID)에서 읽는다. .env 는 Git 에 올라가지 않는다.
set -euo pipefail
cd "$(dirname "$0")/../../.."

IMAGE="${IMAGE:-snipy-frontend:onprem}"
# 브라우저로 접속할 주소. redirect URI(<주소>/auth/<provider>/callback)가 이 주소로 만들어지니
# 각 provider 콘솔과 백엔드의 *_REDIRECT_URI도 같은 값으로 맞춘다.
BASE_URL="${BASE_URL:-http://localhost:3000}"
WORKERS="${WORKERS:-192.168.122.21 192.168.122.22}"
SSH_USER="${SSH_USER:-k8s}"
# 터널(localhost)이면 구글도 쓸 수 있어 카카오·구글을 켠다. 구글 ID 가 .env 에 없으면 SOCIAL_PROVIDERS=kakao 로 줄인다.
SOCIAL_PROVIDERS="${SOCIAL_PROVIDERS:-kakao,google}"

KAKAO_ID="${VITE_KAKAO_CLIENT_ID:-}"
if [ -z "$KAKAO_ID" ] && [ -f .env ]; then
  KAKAO_ID="$(grep '^KAKAO_CLIENT_ID=' .env | head -1 | cut -d= -f2-)"
fi
[ -n "$KAKAO_ID" ] || { echo "!! 카카오 REST API 키가 없습니다 (.env 의 KAKAO_CLIENT_ID 또는 환경 변수 VITE_KAKAO_CLIENT_ID)" >&2; exit 1; }

# 구글 클라이언트 ID는 공개 값이다. 시크릿은 백엔드에만 있고 여기서는 쓰지 않는다.
GOOGLE_ID="${VITE_GOOGLE_CLIENT_ID:-}"
if [ -z "$GOOGLE_ID" ] && [ -f .env ]; then
  GOOGLE_ID="$(grep '^GOOGLE_CLIENT_ID=' .env | head -1 | cut -d= -f2-)"
fi
case ",$SOCIAL_PROVIDERS," in
  *,google,*)
    [ -n "$GOOGLE_ID" ] || { echo "!! SOCIAL_PROVIDERS 에 google 이 있는데 구글 클라이언트 ID 가 없습니다 (.env 의 GOOGLE_CLIENT_ID)" >&2; exit 1; }
    case "$BASE_URL" in
      http://localhost*|https://*) ;;
      *) echo "!! 구글은 localhost 또는 HTTPS 주소만 리디렉션 URI 로 받습니다. BASE_URL=$BASE_URL 로는 구글 로그인이 되지 않습니다." >&2; exit 1 ;;
    esac ;;
esac

echo ">> 1/3 이미지 빌드: $IMAGE (접속 주소 $BASE_URL, 로그인 방식 $SOCIAL_PROVIDERS)"
docker build -f src/frontend/Dockerfile.prod -t "$IMAGE" \
  --build-arg VITE_SOCIAL_PROVIDERS="$SOCIAL_PROVIDERS" \
  --build-arg VITE_KAKAO_CLIENT_ID="$KAKAO_ID" \
  --build-arg VITE_KAKAO_REDIRECT_URI="$BASE_URL/auth/kakao/callback" \
  --build-arg VITE_GOOGLE_CLIENT_ID="$GOOGLE_ID" \
  --build-arg VITE_GOOGLE_REDIRECT_URI="$BASE_URL/auth/google/callback" \
  --label "org.opencontainers.image.source=https://github.com/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03" \
  src/frontend

# PUSH=1 이면 워커에 넣지 않고 GHCR 로 올린다. 조직의 패키지는 여러 팀이 같이 쓰므로 이름에 저장소 이름이 들어가야 한다.
if [ "${PUSH:-}" = 1 ]; then
  case "$IMAGE" in
    ghcr.io/*) ;;
    *) echo "!! PUSH=1 이면 IMAGE 가 ghcr.io/... 여야 합니다 (현재 $IMAGE)" >&2; exit 1 ;;
  esac
  echo ">> 2/2 GHCR 로 push: $IMAGE"
  docker push "$IMAGE"
  echo "완료. 조직 Packages 에서 패키지를 확인하고, 클러스터에서 pull 하려면 Public 으로 바꾼다 (infra/k8s/overlays/dev/README.md 참고)"
  exit 0
fi

TAR="$(mktemp -d)/snipy-frontend.tar"
echo ">> 2/3 tar 로 저장: $TAR"
docker save -o "$TAR" "$IMAGE"

echo ">> 3/3 워커에 전달: $WORKERS"
for w in $WORKERS; do
  echo "   - $w"
  scp "$TAR" "$SSH_USER@$w:/tmp/snipy-frontend.tar"
  # kubelet은 containerd의 k8s.io 네임스페이스 이미지만 본다.
  ssh -t "$SSH_USER@$w" "sudo ctr -n k8s.io images import /tmp/snipy-frontend.tar && rm -f /tmp/snipy-frontend.tar"
done

echo "완료. 배포는 infra/k8s/overlays/dev/README.md 참고 (이미 떠 있으면 cp1 에서 kubectl -n snipy rollout restart deploy/frontend)"
