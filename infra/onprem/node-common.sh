#!/usr/bin/env bash
# 모든 노드(cp1, w1, w2)에서 root 로 실행: kubeadm 설치 전 공통 설정.
#   bash /home/k8s/node-common.sh <호스트명>      예) bash /home/k8s/node-common.sh k8s-cp1   (sudo -i 로 root 가 된 뒤)
# 하는 일: 호스트명/hosts, swap 끄기, 커널 모듈·sysctl, containerd(SystemdCgroup), kubeadm/kubelet/kubectl 설치.
# 설계 값은 ON-guide.md (1부) 참고. 여러 번 실행해도 안전하다.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "root 로 실행하세요 (sudo -i)"; exit 1; }
HOST="${1:?호스트명을 지정하세요: k8s-cp1 | k8s-w1 | k8s-w2}"
K8S_MINOR="${K8S_MINOR:-v1.31}"   # 설치할 쿠버네티스 마이너 버전

echo ">> 1/6 호스트명·hosts"
hostnamectl set-hostname "$HOST"
sed -i '/# k8s-lab/d' /etc/hosts
cat >> /etc/hosts <<EOF
192.168.122.11 k8s-cp1 # k8s-lab
192.168.122.21 k8s-w1  # k8s-lab
192.168.122.22 k8s-w2  # k8s-lab
EOF

echo ">> 2/6 swap 끄기 (kubelet 요구사항)"
swapoff -a
sed -i '/ swap / s/^\(.*\)$/#\1/' /etc/fstab

echo ">> 3/6 커널 모듈·sysctl"
cat > /etc/modules-load.d/k8s.conf <<EOF
overlay
br_netfilter
EOF
modprobe overlay; modprobe br_netfilter
cat > /etc/sysctl.d/k8s.conf <<EOF
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sysctl --system >/dev/null

echo ">> 4/6 containerd"
export DEBIAN_FRONTEND=noninteractive
# 자동 업데이트(unattended-upgrades)가 dpkg 잠금을 잡고 있으면 최대 10분 기다린다 (DPkg::Lock::Timeout)
apt-get -o DPkg::Lock::Timeout=600 update -qq
apt-get -o DPkg::Lock::Timeout=600 install -y -qq containerd conntrack socat ebtables ethtool apt-transport-https ca-certificates curl gpg >/dev/null
mkdir -p /etc/containerd
containerd config default > /etc/containerd/config.toml
# kubelet 과 같은 cgroup 드라이버(systemd)를 쓰도록 맞춘다. 안 맞으면 파드가 계속 재시작된다.
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
systemctl enable --now containerd
systemctl restart containerd

echo ">> 5/6 kubeadm·kubelet·kubectl ($K8S_MINOR)"
mkdir -p /etc/apt/keyrings
curl -fsSL "https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/Release.key" | gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/ /" > /etc/apt/sources.list.d/kubernetes.list
apt-get -o DPkg::Lock::Timeout=600 update -qq
apt-get -o DPkg::Lock::Timeout=600 install -y -qq kubelet kubeadm kubectl >/dev/null
apt-mark hold kubelet kubeadm kubectl >/dev/null
systemctl enable kubelet

echo ">> 6/6 확인"
echo "containerd: $(containerd --version | awk '{print $3}')  kubeadm: $(kubeadm version -o short)  swap: $(swapon --show | wc -l)줄(0이어야 함)"
echo "완료. control-plane 이면 cp-init.sh, worker 이면 cp1 에서 출력된 join 명령을 실행하세요."
