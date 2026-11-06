#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: kubeadm init + Calico(CNI). root 로 실행하면 kubeconfig 가 /root/.kube/config 에 만들어진다.
#   bash /home/k8s/cp-init.sh
# 전제: node-common.sh 를 먼저 실행했을 것. 설계 값은 ON-guide.md (1부) 참고.
set -euo pipefail
CP_IP=192.168.122.11
POD_CIDR=10.244.0.0/16        # VM 서브넷(192.168.x.x)과 겹치지 않게. Calico 기본값을 쓰면 겹친다.
CALICO_VERSION="${CALICO_VERSION:-v3.28.2}"

echo ">> 1/4 kubeadm init"
kubeadm init \
  --apiserver-advertise-address="$CP_IP" \
  --pod-network-cidr="$POD_CIDR" \
  --node-name k8s-cp1

echo ">> 2/4 kubeconfig"
mkdir -p "$HOME/.kube"
cp -f /etc/kubernetes/admin.conf "$HOME/.kube/config"
chown "$(id -u):$(id -g)" "$HOME/.kube/config"

echo ">> 3/4 Calico ($CALICO_VERSION)"
kubectl create -f "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/tigera-operator.yaml"
curl -fsSL "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/custom-resources.yaml" \
  | sed "s|cidr: 192.168.0.0/16|cidr: ${POD_CIDR}|" | kubectl create -f -

echo ">> 4/4 노드 상태 (Calico 가 올라올 때까지 NotReady 일 수 있음)"
kubectl get nodes
echo
echo "worker 노드(w1, w2)에서 아래 명령을 root 로 실행하세요:"
kubeadm token create --print-join-command
echo
echo "그 뒤 cp1 에서 bash /home/k8s/cp-addons.sh 를 실행하세요."
