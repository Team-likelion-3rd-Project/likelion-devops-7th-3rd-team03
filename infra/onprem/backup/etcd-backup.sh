#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: etcd 스냅샷 백업.
#   bash /home/k8s/backup/etcd-backup.sh
# kubeadm 이 띄운 etcd 파드 안의 etcdctl 로 스냅샷을 만든다 (cp1 에 etcdctl 을 설치하지 않는다).
# 파드의 /var/lib/etcd 가 cp1 의 /var/lib/etcd 와 같은 디렉터리(hostPath)라서, 만든 파일을 cp1 에서 바로 꺼낸다.
# 스냅샷에는 클러스터의 모든 Secret 이 들어 있다 (Sealed Secrets 개인키 포함). 파일은 root 만 읽게 두고, 밖으로 복사할 때 조심한다.
# 환경 변수: BACKUP_DIR (기본 /var/backups/snipy), KEEP (보관 개수, 기본 7)
set -euo pipefail
umask 077

export KUBECONFIG="${KUBECONFIG:-/etc/kubernetes/admin.conf}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/snipy}/etcd"
KEEP="${KEEP:-7}"
POD="etcd-$(hostname)"
ETCD_DIR=/var/lib/etcd
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="etcd-$TS.db"

etcdctl() {
  kubectl -n kube-system exec "$POD" -- etcdctl \
    --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key "$@"
}

# 실패하면 etcd 데이터 디렉터리에 남은 임시 파일을 지운다 (성공하면 이미 옮겨져서 지울 것이 없다)
trap 'rm -f "$ETCD_DIR/$NAME"' EXIT

echo "[$(date -u +%FT%TZ)] etcd 백업 시작: $NAME"
mkdir -p "$BACKUP_DIR"

echo ">> 1/3 스냅샷 만들기"
etcdctl snapshot save "$ETCD_DIR/$NAME"

echo ">> 2/3 스냅샷 검증 (해시, 리비전, 키 수)"
# etcdctl 3.5 는 snapshot status 가 deprecated 경고를 내지만 동작한다. 3.6 이상이면 etcdutl 로 바꾼다.
kubectl -n kube-system exec "$POD" -- etcdctl snapshot status "$ETCD_DIR/$NAME" -w table

echo ">> 3/3 cp1 백업 디렉터리로 옮기고 오래된 것 정리 (최근 $KEEP 개 보관)"
[ -s "$ETCD_DIR/$NAME" ] || { echo "!! cp1 의 $ETCD_DIR 에서 스냅샷 파일을 찾지 못했습니다."; exit 1; }
mv "$ETCD_DIR/$NAME" "$BACKUP_DIR/$NAME"
chmod 600 "$BACKUP_DIR/$NAME"
ls -1t "$BACKUP_DIR"/etcd-*.db | tail -n +"$((KEEP + 1))" | xargs -r rm -f --

echo "[$(date -u +%FT%TZ)] 완료: $BACKUP_DIR/$NAME ($(du -h "$BACKUP_DIR/$NAME" | cut -f1))"
