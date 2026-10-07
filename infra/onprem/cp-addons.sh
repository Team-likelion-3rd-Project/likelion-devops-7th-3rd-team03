#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: 노드 3대가 Ready 가 된 뒤 부가 구성요소 설치.
#   bash /home/k8s/cp-addons.sh
# 설치: metrics-server, ingress-nginx(NodePort 30080/30443), local-path-provisioner(기본 StorageClass)
set -euo pipefail

echo ">> 0/3 노드 확인"
kubectl get nodes
[ "$(kubectl get nodes --no-headers | grep -c ' Ready')" -ge 3 ] || { echo "!! Ready 노드가 3개 미만입니다. 워커 join 과 Calico 상태를 확인하세요."; exit 1; }

echo ">> 1/3 metrics-server"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
# 로컬 VM 의 kubelet 인증서는 자체 서명이라 검증을 끈다 (실습 환경 한정).
kubectl -n kube-system patch deploy metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

echo ">> 2/3 ingress-nginx (bare-metal, NodePort)"
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.2/deploy/static/provider/baremetal/deploy.yaml
kubectl -n ingress-nginx patch svc ingress-nginx-controller --type=json -p '[
  {"op":"replace","path":"/spec/ports/0/nodePort","value":30080},
  {"op":"replace","path":"/spec/ports/1/nodePort","value":30443}]'

echo ">> 3/3 local-path-provisioner (기본 StorageClass)"
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.30/deploy/local-path-storage.yaml
kubectl patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'

echo
echo "잠시 후 확인:  kubectl top nodes   /   kubectl get pods -A"
echo "Ingress 접속:  http://192.168.122.21:30080  (아직 Ingress 규칙이 없으면 404 가 정상)"
