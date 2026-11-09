# 온프렘 백업 (etcd, MySQL)

클러스터(ON-guide, `cp-addons.sh`까지 끝난 상태)의 etcd와 MySQL을 cp1에 정기적으로 백업하고, 복구가 되는지 확인하는 방법이다. 팀원마다 클러스터가 따로 있으므로 **각자 자기 cp1에서** 설치한다.

- `etcd-backup.sh`: etcd 스냅샷. etcd 파드 안의 `etcdctl`을 쓴다 (cp1에 설치하지 않는다)
- `mysql-backup.sh`: `shortlink` DB 덤프. `mysql-0` 안의 `mysqldump`를 쓴다
- `s3-upload.sh`: 위 두 백업의 최신 파일을 S3로 한 부 더 올린다 ([4. S3 업로드](#4-s3-업로드))

## 무엇이 어디에 남나

| 대상 | 파일 | 위치 (cp1) | 보관 |
|---|---|---|---|
| etcd | `etcd-<UTC 시각>.db` | `/var/backups/snipy/etcd/` | 최근 7개 |
| MySQL | `mysql-shortlink-<UTC 시각>.sql.gz` | `/var/backups/snipy/mysql/` | 최근 7개 |

- 시각은 UTC다. 예: `etcd-20261029T180000Z.db`
- MySQL 데이터는 워커(local-path)에 있고 백업은 cp1에 둔다. 워커 한 대가 망가져도 덤프가 남는다.
- **한계**: VM 세 대가 모두 같은 PC에 있어서, PC 디스크가 망가지면 백업도 같이 사라진다. 중요한 시점에는 아래 [호스트로 복사](#호스트-pc로-복사)로 PC의 다른 위치에 한 부 더 둔다. S3에 올려 두면 PC가 사라져도 남는다 ([4. S3 업로드](#4-s3-업로드)).

## 1. 설치

호스트(저장소 루트)에서 cp1로 복사한다.

```bash
scp -r infra/onprem/backup k8s@192.168.122.11:~/
```

cp1에서 root로 한 번씩 실행해 본다.

```bash
bash /home/k8s/backup/etcd-backup.sh
bash /home/k8s/backup/mysql-backup.sh
```

각각 `완료: ...` 줄이 나오면 된다. 파일이 생겼는지 본다.

```bash
ls -lh /var/backups/snipy/etcd /var/backups/snipy/mysql
```

## 2. 정기 실행 (cron)

cron이 설치되어 있어야 한다. 최소 설치 이미지에는 없을 수 있다 (`systemctl is-active cron`이 `inactive`이거나 `Unit cron.service could not be found`가 나온다). 없으면 cp1에서 root로 설치하고 켠다. `enabled`여야 VM을 재부팅해도 다시 켜진다.

```bash
apt-get install -y cron
systemctl enable --now cron
systemctl is-active cron; systemctl is-enabled cron
```

`active`, `enabled`가 나오면 `/etc/cron.d/snipy-backup`을 만든다. VM의 시간대는 UTC라서 아래는 한국 시간 15:00, 15:10이다. VM이 꺼져 있는 시각에는 cron이 돌지 않으므로, VM이 켜져 있는 시간대로 잡는다 (새벽에 꺼 두는 환경에서 새벽 시각으로 잡았다가 일주일 동안 백업이 없었던 적이 있다).

```bash
cat > /etc/cron.d/snipy-backup <<'EOF'
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 6 * * * root bash /home/k8s/backup/etcd-backup.sh >> /var/log/snipy-backup.log 2>&1
10 6 * * * root bash /home/k8s/backup/mysql-backup.sh >> /var/log/snipy-backup.log 2>&1
EOF
```

실행 결과는 `/var/log/snipy-backup.log`에 쌓인다. 다음 날 확인한다.

```bash
tail -n 20 /var/log/snipy-backup.log
ls -lh /var/backups/snipy/etcd /var/backups/snipy/mysql
```

백업이 실패해도 알림은 가지 않는다. 로그에 `!!`가 있거나 새 파일이 안 생겼으면 실패한 것이다.

## 3. 복구 시험

백업 파일이 있다고 복구가 되는 것은 아니다. 운영 데이터는 건드리지 않고 복원이 되는지만 확인한다.

### MySQL: 임시 DB에 복원해 행 수 비교

백업 직후에 하면 운영 DB와 행 수가 같아야 한다 (그 사이 데이터가 바뀌면 달라질 수 있다). cp1에서 root로 실행한다.

```bash
bash /home/k8s/backup/mysql-backup.sh
F=$(ls -1t /var/backups/snipy/mysql/mysql-shortlink-*.sql.gz | head -1); echo $F
```

임시 DB를 만들고 복원한다.

```bash
kubectl -n snipy exec mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE shortlink_restore"'
zcat "$F" | kubectl -n snipy exec -i mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot shortlink_restore'
```

운영 DB와 행 수를 비교한다. 위에서부터 짝을 이룬 숫자(users 운영·복원, links 운영·복원)가 서로 같으면 된다.

```bash
kubectl -n snipy exec mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT COUNT(*) FROM shortlink.users; SELECT COUNT(*) FROM shortlink_restore.users; SELECT COUNT(*) FROM shortlink.links; SELECT COUNT(*) FROM shortlink_restore.links"'
```

테이블 목록도 같은지 본다. 앞 6줄과 뒤 6줄이 같아야 한다 (`link_daily_dimension_stats`, `link_daily_stats`, `links`, `refresh_tokens`, `schema_history`, `users`).

```bash
kubectl -n snipy exec mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SHOW TABLES FROM shortlink; SHOW TABLES FROM shortlink_restore"'
```

운영 DB가 비어 있어 행 수가 모두 0이면 데이터 복원까지는 증명되지 않는다. 그때는 `DB=임시DB이름 bash mysql-backup.sh`로 샘플 행을 넣은 임시 DB를 덤프해서 같은 방식으로 시험한다.

끝나면 임시 DB를 지운다.

```bash
kubectl -n snipy exec mysql-0 -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "DROP DATABASE shortlink_restore"'
```

### etcd: 스냅샷 검증과 임시 디렉터리 복원

스냅샷 검증은 백업할 때마다 스크립트가 한다 (해시, 리비전, 키 수). 여기서는 실제로 복원이 되는지 임시 디렉터리에 풀어 본다. 파드 안에서 풀기 위해 etcd 데이터 디렉터리(`/var/lib/etcd`) 아래에 임시 이름으로 두고, 끝나면 **정확히 이 이름만** 지운다.

```bash
F=$(ls -1t /var/backups/snipy/etcd/etcd-*.db | head -1); echo $F
cp "$F" /var/lib/etcd/restore-src.db
kubectl -n kube-system exec etcd-$(hostname) -- etcdctl snapshot restore /var/lib/etcd/restore-src.db --data-dir /var/lib/etcd/restore-test
ls /var/lib/etcd/restore-test/member
```

`member` 디렉터리가 보이면 복원이 된 것이다. 정리한다.

```bash
rm -rf /var/lib/etcd/restore-test /var/lib/etcd/restore-src.db
```

`/var/lib/etcd/member`는 실제 etcd 데이터라서 지우면 안 된다. 위 두 이름 외에는 건드리지 않는다.

### 실제 장애 복구 (시험하지 않음)

운영 클러스터의 etcd를 되돌리는 절차는 클러스터를 멈춰야 해서 시험하지 않았다. kubeadm 클러스터의 일반 절차는 다음과 같다. 필요해지면 쿠버네티스 공식 문서의 "etcd 백업과 복원"을 같이 보고 진행한다.

1. `/etc/kubernetes/manifests/etcd.yaml`을 다른 곳으로 옮겨 etcd를 멈춘다.
2. `/var/lib/etcd`를 `/var/lib/etcd.broken` 등으로 바꿔 둔다.
3. 스냅샷을 `--data-dir /var/lib/etcd`로 복원한다 (`etcdutl snapshot restore` 또는 `etcdctl snapshot restore`).
4. 매니페스트를 되돌려 etcd를 다시 띄우고 `kubectl get nodes`로 확인한다.

MySQL 복구는 위 복구 시험과 같다. 새 클러스터라면 마이그레이션 Job이 DB와 계정을 먼저 만든 뒤, `shortlink`에 덤프를 넣는다 (`mysql -uroot shortlink < 덤프`). 덤프에 `DROP TABLE IF EXISTS`가 들어 있어 기존 테이블을 덮어쓴다.

## 4. S3 업로드

cp1과 PC가 통째로 사라져도 복구할 수 있게, 최신 백업을 S3에 한 부 더 올린다. 팀의 dev 클러스터(`snipy-dev`) **하나만** 올린다. 개인 연습용 클러스터는 올리지 않는다.

| 항목 | 내용 |
|---|---|
| 스크립트 | `s3-upload.sh`: `etcd-backup.sh`, `mysql-backup.sh`가 만든 최신 파일을 종류별로 하나씩 올린다 |
| 위치 | `s3://<버킷>/onprem/snipy-dev/etcd/`, `.../mysql/` |
| 보관 | 버킷 수명주기 14일. 스크립트는 S3의 파일을 지우지 않는다 |
| 암호화 | 서버 측 암호화(기본 `AES256`), 비공개 버킷 |
| 올리는 키 | 위 경로에 쓰기(`PutObject`)만 허용. 목록 조회·삭제는 안 된다 |

로컬 백업은 그대로 두고, 업로드가 실패해도 로컬 백업에는 영향이 없다. 올린 파일 이름은 `/var/backups/snipy/.s3-uploaded`에 적어 같은 파일을 다시 올리지 않는다.
etcd 스냅샷에는 모든 Secret이 들어 있으므로 버킷을 공개하지 않고, 읽기 권한은 필요한 사람에게만 준다.

### 설치

버킷 이름과 업로드용 IAM 키는 클라우드 담당에게 받는다. cp1에서 root로 실행한다. `s3-upload.sh`는 [1. 설치](#1-설치)에서 폴더째 복사하므로 같이 간다 (이미 설치했다면 다시 복사한다).

AWS CLI가 없으면 설치한다 (`unzip`이 없으면 먼저 설치한다).

```bash
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install
aws --version
```

업로드용 키를 `snipy-backup` 프로필로 저장한다. 키는 이 파일 밖(저장소, 메신저, 스크립트)에 적지 않는다.

```bash
aws configure --profile snipy-backup
chmod 600 /root/.aws/credentials
```

설정 파일에는 키가 아니라 버킷과 클러스터 이름만 적는다. `CLUSTER`는 S3 경로이면서 IAM 권한 범위라서, 클라우드 담당과 정한 값(`snipy-dev`)과 같아야 한다.

```bash
cat > /etc/snipy-backup-s3.env <<'EOF'
S3_BUCKET=<버킷 이름>
CLUSTER=snipy-dev
EOF
chmod 600 /etc/snipy-backup-s3.env
```

한 번 실행해 본다.

```bash
bash /home/k8s/backup/s3-upload.sh
```

종류마다 `업로드 완료` 줄이 나오면 된다. 다시 실행하면 `이미 올렸습니다`가 나온다.

도착했는지는 업로드 키가 아니라 **읽기 권한이 있는 관리자 자격**으로 본다 (업로드 키는 목록 조회가 안 된다).

```bash
aws s3 ls s3://<버킷>/onprem/snipy-dev/ --recursive --profile <읽기 프로필>
```

### 정기 실행

두 백업(15:00, 15:10 KST)이 끝난 뒤인 15:20 KST에 돌린다. `/etc/cron.d/snipy-backup`에 한 줄을 더한다.

```bash
echo '20 6 * * * root bash /home/k8s/backup/s3-upload.sh >> /var/log/snipy-backup.log 2>&1' >> /etc/cron.d/snipy-backup
```

실패는 로그의 `!!`로 안다. 업로드 실패뿐 아니라, 최신 백업이 30시간보다 오래됐을 때(백업 cron이 멈춘 것)도 `!!`가 나온다. 알림은 아직 가지 않는다.

### S3에서 복원

cp1이 사라졌다면 새 cp1(또는 PC)에서 읽기 권한이 있는 자격으로 받는다.

```bash
aws s3 cp s3://<버킷>/onprem/snipy-dev/mysql/mysql-shortlink-<시각>.sql.gz /tmp/ --profile <읽기 프로필>
aws s3 cp s3://<버킷>/onprem/snipy-dev/etcd/etcd-<시각>.db /tmp/ --profile <읽기 프로필>
```

받은 파일 경로를 `F`로 두고 [3. 복구 시험](#3-복구-시험)과 같은 절차로 확인한다 (MySQL은 임시 DB에 복원해 행 수 비교, etcd는 임시 디렉터리에 복원). 실제 장애 복구는 위 [실제 장애 복구](#실제-장애-복구-시험하지-않음)를 따른다.

## 주의

- **etcd 스냅샷에는 클러스터의 모든 Secret이 들어 있다.** Sealed Secrets의 개인키도 포함된다. 파일은 root만 읽을 수 있게 되어 있고(권한 600), 깃에 올리거나 메신저로 보내지 않는다.
- MySQL 덤프에는 서비스 데이터(사용자, 링크, 클릭 기록)가 들어 있다. 같은 이유로 공개 장소에 두지 않는다.
- 덤프에는 계정과 권한이 없다. 계정은 마이그레이션 Job(`00-database-setup.sql`)이 만든다.
- 스키마를 크게 바꾸는 배포(예: 팀 기능으로 `links`에 컬럼 추가) 전에는 `mysql-backup.sh`를 한 번 수동으로 실행해 둔다.

### 호스트 PC로 복사

호스트의 Git Bash에서 실행한다. 복사한 파일은 레포 폴더 밖에 둔다.

```bash
scp k8s@192.168.122.11:/var/backups/snipy/mysql/mysql-shortlink-<시각>.sql.gz ~/backups/
```

`/var/backups`는 root만 읽을 수 있어서 `k8s` 계정으로는 막힐 수 있다. 그럴 때는 cp1에서 root로 파일을 `/home/k8s/`에 복사하고 소유자를 `k8s`로 바꾼 뒤 가져온다.

## 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| `error: ... Unauthorized` 또는 `connection refused` | kubeconfig를 못 찾음 | 스크립트는 `/etc/kubernetes/admin.conf`를 기본으로 쓴다. root로 실행했는지 본다 |
| etcd 스크립트가 `스냅샷 파일을 찾지 못했습니다` | etcd 파드의 `/var/lib/etcd`와 cp1의 경로가 다름 | `kubectl -n kube-system get pod etcd-$(hostname) -o yaml`의 `volumes`에서 hostPath를 확인한다 |
| MySQL 스크립트가 `덤프가 끝까지 만들어지지 않았습니다` | 도중에 `mysql-0`이 재시작됨 | `kubectl -n snipy get pod mysql-0`을 보고 다시 실행한다 |
| cron이 안 돈다 | cron 파일 권한이나 서비스 | `systemctl status cron`, `/etc/cron.d/snipy-backup`의 소유자가 root인지 본다 |
| S3 업로드가 `AccessDenied` | 키 권한 또는 경로 불일치 | `/etc/snipy-backup-s3.env`의 `S3_BUCKET`, `CLUSTER`가 클라우드 담당과 정한 값인지 본다 |
| `aws CLI 가 없습니다` | AWS CLI 미설치 | [4. S3 업로드](#4-s3-업로드)의 설치를 한다 |
| `최신 백업이 30시간보다 오래됐습니다` | etcd·MySQL 백업 cron이 멈춤 | `/var/log/snipy-backup.log`에서 백업 로그를 먼저 본다 |
| 업로드 키로 `aws s3 ls`가 안 된다 | 정상. 업로드 키는 쓰기만 허용 | 읽기 권한이 있는 자격으로 확인한다 |
