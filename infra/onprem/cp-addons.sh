#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: 노드 3대가 Ready 가 된 뒤 부가 구성요소 설치.
#   bash /home/k8s/cp-addons.sh
# 설치: metrics-server, ingress-nginx(NodePort 30080/30443, 컨트롤러 2개), local-path-provisioner(기본 StorageClass)
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
# 컨트롤러를 2개로 두고 서로 다른 워커에 나눠 올린다. 1개일 때 그 워커를 점검(drain)하면 새 파드가 뜰 때까지 프론트가 약 20초 끊겼다.
# ScheduleAnyway 라서 워커가 한 대뿐이어도 2개가 모두 뜨고, PDB 가 drain 때 둘을 동시에 내리지 못하게 한다.
# 이미 돌고 있는 클러스터에 적용하면 새 파드 2개가 같은 워커에 올라갈 수 있다(옛 파드가 개수에 잡히고 요청량이 적은 쪽이 점수가 높다).
# 적용 뒤 kubectl -n ingress-nginx get pods -o wide 로 서로 다른 노드인지 보고, 같으면 한 개를 지워 다시 올린다.
kubectl -n ingress-nginx patch deploy ingress-nginx-controller --type=merge -p '{"spec":{"replicas":2,"template":{"spec":{"topologySpreadConstraints":[{"maxSkew":1,"topologyKey":"kubernetes.io/hostname","whenUnsatisfiable":"ScheduleAnyway","nodeTaintsPolicy":"Honor","labelSelector":{"matchLabels":{"app.kubernetes.io/name":"ingress-nginx","app.kubernetes.io/component":"controller"}}}]}}}}'
kubectl apply -f - <<'PDB'
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: ingress-nginx
      app.kubernetes.io/component: controller
PDB

echo ">> 3/3 local-path-provisioner (기본 StorageClass)"
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.30/deploy/local-path-storage.yaml
kubectl patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'

echo
echo "잠시 후 확인:  kubectl top nodes   /   kubectl get pods -A"
echo "Ingress 접속:  http://192.168.122.21:30080  (아직 Ingress 규칙이 없으면 404 가 정상)"
