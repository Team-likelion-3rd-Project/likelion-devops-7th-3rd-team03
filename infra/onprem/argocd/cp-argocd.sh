#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: Argo CD 설치.
#   bash /home/k8s/argocd/cp-argocd.sh
# 설치: Argo CD (argocd 네임스페이스). 쓰지 않는 dex 는 0으로 줄여 메모리를 아낀다.
# notifications 는 켜 둔다. Slack 웹훅을 넣어야 알림이 간다 (README 6번).
# 버전은 ARGOCD_VERSION 으로 바꿀 수 있다 (기본 v3.3.16).
# 클러스터는 Kubernetes v1.31.14 이고, Argo CD 3.3 이상의 공식 시험 범위는 v1.32 부터다.
# 이 클러스터에서 v3.3.16 설치, 앱 동기화(Synced, Healthy), selfHeal 동작을 확인했다.
# 다른 환경에서 문제가 있으면 v3.2.12 (v1.31 시험됨, 패치 종료)로 바꿔 본다.
set -euo pipefail
ARGOCD_VERSION="${ARGOCD_VERSION:-v3.3.16}"

echo ">> 0/4 노드 확인"
kubectl get nodes
[ "$(kubectl get nodes --no-headers | grep -c ' Ready')" -ge 3 ] || { echo "!! Ready 노드가 3개 미만입니다."; exit 1; }

echo ">> 1/4 네임스페이스 argocd"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -

echo ">> 2/4 Argo CD $ARGOCD_VERSION 설치 (server-side apply: CRD 가 커서 필요)"
kubectl apply -n argocd --server-side --force-conflicts \
  -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"

echo ">> 3/4 쓰지 않는 구성요소 줄이기 (dex: SSO) / 배포 실패 알림 설정(notifications.yaml) 적용"
kubectl -n argocd scale deploy argocd-dex-server --replicas=0
kubectl apply -f "$(dirname "$0")/notifications.yaml"

echo ">> 4/4 기동 대기 (최대 5분씩)"
kubectl -n argocd rollout status deploy/argocd-redis --timeout=300s
kubectl -n argocd rollout status deploy/argocd-repo-server --timeout=300s
kubectl -n argocd rollout status deploy/argocd-server --timeout=300s
kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=300s

echo
echo "완료. 확인:  kubectl -n argocd get pods   /   kubectl top pods -n argocd"
echo "UI 접속:     kubectl -n argocd port-forward --address 0.0.0.0 svc/argocd-server 8443:443   (이 터미널은 접속하는 동안 켜 둔다)"
echo "             호스트 브라우저에서 https://192.168.122.11:8443  (자체 서명 인증서 경고는 넘어간다)"
echo "초기 비밀번호는 화면에만 출력되게 직접 확인한다 (채팅에 붙이지 않는다):"
echo "  kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo"
