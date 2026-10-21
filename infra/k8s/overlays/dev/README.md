# dev overlay (온프렘 VM 클러스터)

온프렘 VM 클러스터(cp1 + 워커 2대, [ON-guide.md](../../../onprem/ON-guide.md))에 dev 환경 전체를 올린다. 지금은 `kubectl apply -k`로 수동 배포한다. 자동 배포(GHCR CI, ArgoCD)는 후속 이슈에서 붙인다.

```
브라우저 -> 워커:30080 (ingress-nginx) -> /api -> management-service ─┐
                                        -> /    -> frontend           ├─ mysql, redis
브라우저 -> 워커:30081 (NodePort)        -> /{slug} -> redirect-service ┘
                                                      │ 클릭 로그 파일
                                                      ▼
                                              Fluent Bit(사이드카) -> seaweedfs
                                                                        │
                                              click-stats-batch(매일 02:00) -> mysql
```

| 파일 | 내용 |
|---|---|
| `kustomization.yaml` | base + 아래 리소스, `snipy` 네임스페이스, GHCR 이미지 |
| `configmaps.yaml` | management·redirect 설정값 (클라우드는 Terraform이 만드는 값) |
| `mysql.yaml` | MySQL 1대 (StatefulSet, local-path 5Gi, UTC) |
| `redis.yaml` | Redis 1대 (영구 저장 없음) |
| `migration-job.yaml` | `src/db` 마이그레이션 Job |
| `frontend-*.yaml` | 프론트 (이미지는 [infra/onprem/frontend](../../../onprem/frontend/README.md)) |
| `ingress.yaml` | 30080: `/api`, `/` |
| `patch-redirect-service-nodeport.yaml` | 30081: 단축 URL |
| `patch-app-resources.yaml`, `patch-hpa.yaml` | 워커 2대에 맞춰 파드 1개, 요청량 축소 |
| `seaweedfs.yaml` | 클릭 로그 저장소 (S3 호환, local-path 5Gi) + 버킷 생성 Job |
| `patch-redirect-fluent-bit.yaml`, `fluent-bit.conf` | redirect-service Fluent Bit 사이드카 (클릭 로그 → SeaweedFS, 5분 주기) |
| `click-stats-batch-cronjob.yaml` | DuckDB 일별 집계 배치 (KST 02:00, 전날 데이터) |

AWS 전용(`components/aws`)과 Prometheus Operator 전용(`components/monitoring`) 리소스는 넣지 않는다.

## 사전 준비

1. 네임스페이스와 시크릿: [infra/onprem/secrets/README.md](../../../onprem/secrets/README.md)의 7번까지 한다. `mysql-secrets`, `management-service-secrets`, `redirect-service-secrets`, `seaweedfs-s3-secrets`가 `snipy`에 있어야 한다.
2. 카카오 콘솔에 `http://192.168.122.21:30080/auth/kakao/callback`을 등록한다.
3. 프론트 이미지를 워커에 넣는다: `bash infra/onprem/frontend/build-and-load.sh`
4. 백엔드·마이그레이션 이미지를 GHCR에 올린다 (아래).

## GHCR 이미지 올리기 (CI 분리 전까지 수동)

`kustomization.yaml`의 `images:` 태그(`manual-1`)와 같은 태그로 올린다. 호스트(저장소 루트)에서:

```bash
# GitHub 개인 토큰(classic, write:packages 권한)으로 로그인
echo <토큰> | docker login ghcr.io -u <GitHub 사용자명> --password-stdin

R=ghcr.io/team-likelion-3rd-project
docker build -t $R/management-service:manual-1 src/management-service
docker build -t $R/redirect-service:manual-1   src/redirect-service
docker build -t $R/db-migration:manual-1       src/db
docker push $R/management-service:manual-1
docker push $R/redirect-service:manual-1
docker push $R/db-migration:manual-1
```

처음 올린 패키지는 private이다. GitHub 조직의 Packages에서 세 패키지를 **public**으로 바꾼다. public이면 클러스터에 pull 시크릿이 필요 없다. 조직 설정에서 public 패키지를 막아 두었으면 조직 관리자에게 요청한다.

### public으로 바꿀 수 없을 때 (pull 시크릿)

private 그대로 쓰려면 클러스터에만 GHCR 로그인 정보를 넣는다. 레포에는 아무것도 커밋하지 않는다. 클러스터에 저장되는 값이니 push용과 별도로 `read:packages` 권한만 있는 토큰(classic)을 만들어 쓴다.

cp1에서 (`snipy` 네임스페이스가 있은 뒤, 배포 전에):

```bash
read -s -p "GHCR read token: " T && \
kubectl -n snipy create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=<GitHub 사용자명> --docker-password="$T"; unset T

# management·마이그레이션·프론트는 default, redirect는 redirect-service-sa를 쓴다.
# redirect-service-sa는 apply 때 만들어지니 미리 만들어 둔다.
kubectl -n snipy create serviceaccount redirect-service-sa
kubectl -n snipy patch serviceaccount default             -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
kubectl -n snipy patch serviceaccount redirect-service-sa -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
```

처음 apply할 때 `redirect-service-sa`에 `missing the kubectl.kubernetes.io/last-applied-configuration annotation` 경고가 나오는데 무시해도 된다. 매니페스트에 없는 `imagePullSecrets`는 apply를 다시 해도 남는다.

코드를 바꿔 다시 올릴 때는 태그를 올리고(`manual-2`) `kustomization.yaml`도 같은 값으로 바꾼다. 같은 태그로 덮어쓰면 워커에 남은 옛 이미지를 계속 쓴다.

## 배포

`overlays/dev`가 `../../base`를 참조하니 `infra/k8s` 폴더 통째로 cp1에 복사한다.

```bash
scp -r infra/k8s k8s@192.168.122.11:~/
```

cp1(root)에서 한다. 이 문서의 `kubectl` 명령은 모두 cp1에서 실행한다. 호스트 PC의 kubectl은 다른 클러스터(예: 예전 EKS)를 가리키고 있을 수 있다.

```bash
kubectl kustomize ~/k8s/overlays/dev | less   # 적용될 내용 확인
kubectl apply -k ~/k8s/overlays/dev
kubectl -n snipy get pods -w
```

MySQL이 뜨기 전에는 `db-migration` Job이 실패하고 다시 시도한다. Job이 `Complete`가 되고 나서 management·redirect 파드가 `Running`, `READY 1/1`이 되면 된다(redirect는 Fluent Bit 사이드카까지 `2/2`). 앱이 DB 연결에 실패해 재시작하는 경우가 있는데, 마이그레이션이 끝나면 정상으로 돌아온다.

마이그레이션 결과는 `Completed`인 파드의 로그로 본다. 실패한 시도가 남아 있으면 `kubectl logs job/db-migration`은 그중 하나(`30초 안에 접속하지 못했습니다`)를 보여 줄 수 있다.

```bash
kubectl -n snipy get pods -l job-name=db-migration    # STATUS가 Completed인 파드 이름 확인
kubectl -n snipy logs <Completed 파드> | tail -3       # "완료. 모든 마이그레이션이 적용됐습니다."
```

## 확인

- `http://192.168.122.21:30080`에서 로그인 화면이 뜬다
- 카카오 로그인 → 추가정보 입력 → 대시보드. 같은 계정으로 다시 로그인하면 가입 폼 없이 들어간다
- 링크를 만들면 단축 URL이 `http://192.168.122.21:30081/<slug>`로 나오고, 열면 원래 주소로 이동한다
- `kubectl -n snipy delete pod -l app=redis` 후에도 로그인 상태와 리다이렉트가 유지된다 (캐시는 DB에서 다시 채워진다). 오늘의 실시간 클릭 수는 Redis에만 있어서 0으로 돌아간다
- 단축 URL을 열고 5분 뒤 SeaweedFS에 `year=/month=/day=/` 아래 gz 파일이 생긴다
- 배치를 수동으로 돌리면 `link_daily_stats`에 클릭·방문자 수가 들어가고 통계 API에서 보인다. 날짜는 KST 기준이고, 같은 날짜를 여러 번 돌려도 덮어쓰기라 숫자가 중복되지 않는다

```bash
kubectl -n snipy create job batch-manual --from=cronjob/click-stats-batch --dry-run=client -o json \
  | python3 -c 'import sys,json; j=json.load(sys.stdin); j["spec"]["template"]["spec"]["containers"][0]["args"]=["2026-10-20"]; print(json.dumps(j))' \
  | kubectl apply -f -
kubectl -n snipy logs -f job/batch-manual   # 마지막에 "배치 완료. targetDate(KST)=2026-10-20"
```

## 문제 해결

| 증상 | 원인 | 확인 / 해결 |
|---|---|---|
| 앱·MySQL 파드가 `CreateContainerConfigError` | 시크릿이 없다 | `kubectl -n snipy get secret`. 사전 준비 1번 |
| `ImagePullBackOff` (ghcr.io) | 이미지가 없거나 패키지가 private | 태그 확인, 패키지 public 여부. private이면 "public으로 바꿀 수 없을 때"의 pull 시크릿 |
| `db-migration`이 계속 실패 | MySQL 미기동, 비밀번호 불일치 | `kubectl -n snipy logs job/db-migration`, `kubectl -n snipy logs mysql-0` |
| `db-migration`을 다시 적용했더니 `field is immutable` | 끝난 Job이 아직 남아 있다 (10분 뒤 자동 삭제) | `kubectl -n snipy delete job db-migration` 후 다시 적용 |
| frontend 파드가 시작하지 않음, 로그에 `host not found in upstream` | management-service Service가 없다 | 같은 overlay를 통째로 적용했는지 확인 |
| 카카오 로그인 후 400 | 콘솔·`KAKAO_REDIRECT_URI`·접속 주소가 다르다 | 세 값을 같게 맞춘다 |
| MySQL 파드가 `Pending` | PVC가 묶인 노드가 내려가 있다 (local-path) | 그 노드를 살린다 |
| `seaweedfs-bucket-init` 로그에 `!! SeaweedFS 인증 실패` | `seaweedfs-s3-secrets` 값이 SeaweedFS가 뜰 때 읽은 값과 다르다 | 아래 줄 참고 |
| 키를 바꿨는데 Fluent Bit·배치가 403 | SeaweedFS는 기동 시에만 키를 읽는다 | `kubectl -n snipy rollout restart statefulset/seaweedfs` 후 `deploy/redirect-service`도 재시작 |

## 한계

- MySQL·Redis는 1대라 고가용성이 없다. MySQL 데이터는 PVC가 만들어진 워커의 디스크에만 있다. Redis는 영구 저장이 없어 재시작하면 캐시, 가입 대기 세션, 오늘의 실시간 클릭·방문자 수가 사라진다.
- 마이그레이션 Job은 끝나고 10분 뒤 지워진다. 다시 적용하면 다시 실행되지만, 이미 적용된 버전은 건너뛰므로 안전하다.
- 클릭 로그와 Fluent Bit 버퍼는 파드 볼륨(emptyDir)이다. 파드가 지워질 때 종료 대기(30초) 안에 못 올린 로그는 유실된다.
- SeaweedFS도 1대고 데이터는 PVC가 만들어진 워커 디스크에만 있다. 그 노드가 내려가면 수집·배치가 멈춘다 (배치는 SeaweedFS 재기동을 약 90초까지 기다린다).
- 배치는 그 시간(KST 02:00)에 VM이 켜져 있어야 돈다. 놓친 날짜는 위 "확인"의 수동 실행으로 채운다.
- Prometheus Operator가 없어 ServiceMonitor는 넣지 않았다.