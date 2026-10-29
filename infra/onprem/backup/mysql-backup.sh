#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: MySQL 백업.
#   bash /home/k8s/backup/mysql-backup.sh
# mysql-0 파드 안의 mysqldump 로 shortlink DB 를 덤프해 cp1 에 gzip 으로 저장한다.
# 데이터는 워커(local-path)에 있고 백업은 cp1 에 둬서, 워커 한 대가 망가져도 덤프가 남는다.
# 루트 비밀번호는 파드의 환경 변수(mysql-secrets)를 파드 안에서 읽는다. 스크립트·화면·로그에 남기지 않는다.
# --single-transaction: 서비스를 멈추지 않고 한 시점의 일관된 덤프를 만든다 (InnoDB).
# DB 이름만 덤프해서 CREATE DATABASE 가 들어 있지 않다. 다른 이름의 DB 에도 복원할 수 있다 (README 복구 시험).
# 계정과 권한은 넣지 않는다. 마이그레이션 Job(00-database-setup.sql)이 만든다.
# 환경 변수: BACKUP_DIR (기본 /var/backups/snipy), KEEP (보관 개수, 기본 7), DB (기본 shortlink)
set -euo pipefail
umask 077

export KUBECONFIG="${KUBECONFIG:-/etc/kubernetes/admin.conf}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/snipy}/mysql"
KEEP="${KEEP:-7}"
DB="${DB:-shortlink}"
NAMESPACE=snipy
POD=mysql-0
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="mysql-$DB-$TS.sql.gz"
TMP="$BACKUP_DIR/.$NAME.partial"

# 실패하면 덜 만들어진 파일을 지운다 (성공하면 이미 이름이 바뀌어서 지울 것이 없다)
trap 'rm -f "$TMP"' EXIT

echo "[$(date -u +%FT%TZ)] MySQL 백업 시작: $NAME"
mkdir -p "$BACKUP_DIR"

echo ">> 1/3 덤프"
kubectl -n "$NAMESPACE" exec "$POD" -- sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --events '"$DB" \
  | gzip > "$TMP"

echo ">> 2/3 검증 (압축, 끝 표시, 테이블 수)"
gzip -t "$TMP"
zcat "$TMP" | tail -n 1 | grep -q 'Dump completed' || { echo "!! 덤프가 끝까지 만들어지지 않았습니다."; exit 1; }
TABLES="$(zcat "$TMP" | grep -c '^CREATE TABLE' || true)"
[ "$TABLES" -ge 1 ] || { echo "!! 덤프에 테이블이 없습니다."; exit 1; }
echo "테이블 $TABLES 개"

echo ">> 3/3 이름 확정하고 오래된 것 정리 (최근 $KEEP 개 보관)"
mv "$TMP" "$BACKUP_DIR/$NAME"
ls -1t "$BACKUP_DIR"/mysql-"$DB"-*.sql.gz | tail -n +"$((KEEP + 1))" | xargs -r rm -f --

echo "[$(date -u +%FT%TZ)] 완료: $BACKUP_DIR/$NAME ($(du -h "$BACKUP_DIR/$NAME" | cut -f1))"
