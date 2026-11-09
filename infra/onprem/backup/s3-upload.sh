#!/usr/bin/env bash
# control-plane(k8s-cp1)에서 root(sudo -i)로 실행: 로컬 백업(etcd, MySQL)의 최신 파일을 S3로 올린다.
#   bash /home/k8s/backup/s3-upload.sh
# etcd-backup.sh, mysql-backup.sh 가 만든 최신 파일을 올린다. cron 에서 두 백업이 끝난 뒤에 돌린다 (README).
# 로컬 백업은 그대로 둔다. 업로드가 실패해도 로컬 백업에는 영향이 없다.
# 올릴 때 쓰는 키는 해당 prefix 에 쓰기(PutObject)만 허용한다. 그래서 S3 목록 조회·삭제는 하지 않는다.
#   - 이미 올린 파일은 $BACKUP_DIR/.s3-uploaded 에 이름을 적어 두고 건너뛴다.
#   - S3 보관 기간은 버킷 수명주기(14일)가 정한다. 이 스크립트가 지우지 않는다.
#   - 도착했는지는 관리자 자격(읽기 권한)으로 S3 목록을 직접 확인한다. 이 키로는 볼 수 없다.
# etcd 스냅샷에는 클러스터의 모든 Secret 이 들어 있다 (Sealed Secrets 개인키 포함). 버킷은 비공개로 두고 읽기 권한을 최소로 한다.
# AWS 키는 이 파일에 쓰지 않는다. root 의 ~/.aws/credentials 프로필(AWS_PROFILE)에 두고 0600 으로 막는다.
# 설정: /etc/snipy-backup-s3.env (아래 변수를 적는다. 키는 적지 않는다)
#   S3_BUCKET (필수)  버킷 이름
#   CLUSTER           prefix 의 클러스터 이름 (기본: 호스트 이름)
#   S3_PREFIX_BASE    prefix 앞부분 (기본: onprem). 최종 위치는 s3://버킷/onprem/클러스터/etcd|mysql/
#   AWS_PROFILE       자격 프로필 이름 (기본: snipy-backup)
#   S3_SSE            서버 측 암호화 (기본: AES256. SSE-KMS 로 바꾸면 aws:kms 와 S3_KMS_KEY_ID)
#   BACKUP_DIR        로컬 백업 위치 (기본: /var/backups/snipy)
#   MAX_AGE_HOURS     최신 백업이 이보다 오래되면 백업이 멈춘 것으로 보고 경고 (기본: 30)
# 한 종류가 실패해도 다른 종류는 올린다. 하나라도 실패하면 종료 코드 1 이다.
set -uo pipefail
umask 077

ENV_FILE="${ENV_FILE:-/etc/snipy-backup-s3.env}"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

: "${S3_BUCKET:?!! S3_BUCKET 이 없습니다 ($ENV_FILE 에 적는다)}"
CLUSTER="${CLUSTER:-$(hostname)}"
S3_PREFIX_BASE="${S3_PREFIX_BASE:-onprem}"
export AWS_PROFILE="${AWS_PROFILE:-snipy-backup}"
S3_SSE="${S3_SSE:-AES256}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/snipy}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-30}"
PREFIX="$S3_PREFIX_BASE/$CLUSTER"
STATE="$BACKUP_DIR/.s3-uploaded"

command -v aws >/dev/null 2>&1 || { echo "!! aws CLI 가 없습니다. 설치한 뒤 다시 실행한다."; exit 1; }
touch "$STATE"

# upload_latest 종류 파일패턴
#   종류 디렉터리($BACKUP_DIR/종류)에서 가장 최근 파일 하나를 S3 로 올린다.
upload_latest() {
  local kind="$1" pattern="$2" dir="$BACKUP_DIR/$1" file name rc=0 args
  # shellcheck disable=SC2086
  file="$(ls -1t "$dir"/$pattern 2>/dev/null | head -n 1)"
  if [ -z "$file" ]; then
    echo "!! $kind: 올릴 백업 파일이 없습니다 ($dir)"
    return 1
  fi
  name="$(basename "$file")"

  # 최신 파일이 너무 오래됐으면 백업 자체가 멈춘 것이다 (백업 실패는 알림이 가지 않는다)
  if [ -n "$(find "$file" -mmin +"$((MAX_AGE_HOURS * 60))" 2>/dev/null)" ]; then
    echo "!! $kind: 최신 백업이 ${MAX_AGE_HOURS}시간보다 오래됐습니다 ($name). 백업이 실패했을 수 있습니다."
    rc=1
  fi

  if grep -qxF "$name" "$STATE"; then
    echo "$kind: 이미 올렸습니다 ($name)"
    return "$rc"
  fi

  args=(--only-show-errors --sse "$S3_SSE")
  [ -n "${S3_KMS_KEY_ID:-}" ] && args+=(--sse-kms-key-id "$S3_KMS_KEY_ID")
  echo ">> $kind: 업로드 $name ($(du -h "$file" | cut -f1)) -> s3://$S3_BUCKET/$PREFIX/$kind/"
  if aws s3 cp "$file" "s3://$S3_BUCKET/$PREFIX/$kind/$name" "${args[@]}"; then
    echo "$name" >> "$STATE"
    # 상태 파일이 무한히 커지지 않게 최근 30개만 남긴다
    tail -n 30 "$STATE" > "$STATE.tmp" && mv "$STATE.tmp" "$STATE"
    echo "$kind: 업로드 완료 ($name)"
  else
    echo "!! $kind: 업로드에 실패했습니다 ($name). 키 권한, 버킷 이름, 네트워크를 확인한다."
    rc=1
  fi
  return "$rc"
}

echo "[$(date -u +%FT%TZ)] S3 업로드 시작: s3://$S3_BUCKET/$PREFIX/"
fail=0
upload_latest etcd  'etcd-*.db'        || fail=1
upload_latest mysql 'mysql-*.sql.gz'   || fail=1

if [ "$fail" -eq 0 ]; then
  echo "[$(date -u +%FT%TZ)] 완료"
else
  echo "[$(date -u +%FT%TZ)] !! 일부 실패 (위의 !! 줄을 확인한다)"
  exit 1
fi
