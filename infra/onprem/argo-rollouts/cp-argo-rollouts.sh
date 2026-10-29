#!/usr/bin/env bash
# Argo Rollouts 컨트롤러와 kubectl 플러그인을 설치한다. cp1 에서 실행한다.
#   bash /home/k8s/argo-rollouts/cp-argo-rollouts.sh
# 버전은 고정한다. 올릴 때는 릴리스 노트를 보고 VER 만 바꾼다.
set -euo pipefail
VER="${ARGO_ROLLOUTS_VERSION:-v1.8.3}"

echo ">> 1/2 컨트롤러 설치 ($VER)"
kubectl create namespace argo-rollouts --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argo-rollouts -f "https://github.com/argoproj/argo-rollouts/releases/download/$VER/install.yaml"
kubectl -n argo-rollouts rollout status deploy/argo-rollouts --timeout=300s

echo ">> 2/2 kubectl 플러그인 설치"
curl -sLo /tmp/kubectl-argo-rollouts "https://github.com/argoproj/argo-rollouts/releases/download/$VER/kubectl-argo-rollouts-linux-amd64"
sudo install -m 0755 /tmp/kubectl-argo-rollouts /usr/local/bin/kubectl-argo-rollouts
rm -f /tmp/kubectl-argo-rollouts
kubectl argo rollouts version