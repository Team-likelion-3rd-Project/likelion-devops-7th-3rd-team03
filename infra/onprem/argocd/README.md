# 온프렘 Argo CD

클러스터(ON-guide, cp-addons.sh까지 끝난 상태)에 Argo CD를 설치하고 UI에 접속하는 방법이다. 설치와 dev 환경 Application 적용을 다룬다.

- `cp-argocd.sh`: Argo CD 설치 (cp1에서 실행)
- `snipy-dev-app.yaml`: dev 환경(`infra/k8s/overlays/dev`)을 `develop` 브랜치에서 가져오는 Application
- `notifications.yaml`: 배포 실패를 Slack으로 알리는 Argo CD Notifications 설정 ([6번](#6-배포-실패-slack-알림))
- [`rollback.md`](rollback.md): 배포를 이전 이미지로 되돌리는 절차와 리허설 기록

## 배포 방식: self-hosted 러너 대신 Argo CD

온프렘 배포는 GitHub Actions의 self-hosted 러너가 클러스터에 밀어 넣는(push) 방식이 아니라, 클러스터의 Argo CD가 Git을 읽어 가는(pull) 방식이다.

- **공개 저장소**: 누구든 포크해서 PR을 열 수 있는 저장소에 내부망 러너를 두면, PR 코드가 그 러너에서 실행될 수 있다. GitHub도 공개 저장소에서 self-hosted 러너를 권장하지 않는다.
- **클러스터가 팀원 PC마다 따로 있고 사설 NAT 안에 있다**: 러너 방식이면 PC마다 러너가 필요하다.
- **접근 정보가 GitHub에 필요 없다**: push 배포는 kubeconfig나 토큰을 GitHub에 저장해야 하지만, Argo CD는 Git만 읽는다.
- **AWS 단계와 같은 구조**: `infra/argocd`의 GitOps 구조와 같다. CI(호스티드 러너)가 이미지를 빌드해 태그 갱신 커밋을 올리면 Argo CD가 반영한다.
- 롤백은 `git revert`이고, 드리프트는 selfHeal로 복구한다 (자세한 절차는 [`rollback.md`](rollback.md)).

CI 실패는 Actions의 Slack 알림(`notify-failure.yml`)이 알리고, 배포 실패는 Argo CD Notifications가 같은 방식으로 Slack에 알린다 ([6번](#6-배포-실패-slack-알림)).

## 버전과 호환성

기본 설치 버전은 `v3.3.16`이다. 이 클러스터는 Kubernetes v1.31.14인데, Argo CD 3.3 이상의 공식 시험 범위는 v1.32부터다. 이 클러스터에서 설치, UI 로그인, 앱 동기화(`Synced`, `Healthy`), 드리프트 복구(selfHeal), 노드 재부팅 후 정상 동작까지 확인했다.

다른 환경에서 문제가 있으면 `ARGOCD_VERSION=v3.2.12 bash cp-argocd.sh`로 바꿔 본다 (v1.31이 공식 시험 범위에 있으나 더 이상 패치가 나오지 않는다).

## 1. 설치

호스트(저장소 루트)에서 cp1로 복사한다.

```bash
scp -r infra/onprem/argocd k8s@192.168.122.11:~/
```

cp1에서 root로 실행한다. 이미지(`quay.io`, `public.ecr.aws`)를 받는 시간이 있어 몇 분 걸린다.

```bash
bash /home/k8s/argocd/cp-argocd.sh
```

끝나면 파드가 모두 `Running`이어야 한다. dex는 SSO를 쓰지 않아 일부러 0개로 줄였다. notifications는 켜 둔다 (6번).

```bash
kubectl -n argocd get pods
kubectl top pods -n argocd
```

설치 후 노드 메모리 사용률은 38~47%였다. `kubectl top nodes`로 워커 메모리 여유를 확인한다.

## 2. UI 접속

cp1에서 포트 포워딩을 켜 둔다 (접속하는 동안 이 터미널을 유지한다).

```bash
kubectl -n argocd port-forward --address 0.0.0.0 svc/argocd-server 8443:443
```

호스트 브라우저에서 `https://192.168.122.11:8443`을 연다. 자체 서명 인증서 경고는 넘어간다. 사용자는 `admin`이고 초기 비밀번호는 cp1에서 직접 확인한다.

```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
```

출력된 비밀번호를 채팅이나 Git에 붙이지 않는다. 로그인한 뒤 UI의 `User Info → Update Password`로 바꾸고, 바꾼 다음에 초기 비밀번호 Secret을 지운다. Secret만 지우면 비밀번호는 초기값 그대로 남으니 반드시 바꾼 뒤에 지운다.

```bash
kubectl -n argocd delete secret argocd-initial-admin-secret
```

## 3. dev Application 적용

dev 환경을 Argo CD가 `develop`에서 가져오게 한다. 호스트(저장소 루트)에서 cp1로 복사한다.

```bash
scp infra/onprem/argocd/snipy-dev-app.yaml k8s@192.168.122.11:~/argocd/
```

cp1에서 root로 실행한다. 먼저 만들지 않고 서버 검증만 한다.

```bash
kubectl apply --dry-run=server -f /home/k8s/argocd/snipy-dev-app.yaml
```

문제가 없으면 적용하고 상태를 본다.

```bash
kubectl apply -f /home/k8s/argocd/snipy-dev-app.yaml
kubectl -n argocd get app snipy-dev -o jsonpath='{.status.sync.status} {.status.health.status}'; echo
```

- 같은 이름(`snipy-dev`)의 Application이 이미 있으면(예: 시험용 브랜치를 보던 것) 이 파일 내용으로 바뀐다.
- 자동 동기화를 켜고 `prune`(Git에서 사라진 리소스 삭제)과 `selfHeal`(손으로 바꾼 것 자동 복구)은 끈다. `develop`에 머지되면 약 3분 안에 클러스터에 반영된다.
- 동기화가 실패하면(훅 Job 실패 등) Argo CD가 30초, 1분 간격으로 2번 더 시도한다 (`retry`). 그래도 실패하면 원인을 고친 뒤 UI에서 `Sync`를 누른다.
- `db-migration`과 `seaweedfs-bucket-init`은 Argo CD Sync 훅이다. 동기화할 때마다 이전 Job을 지우고(`BeforeHookCreation`) 새로 실행한다. 그래서 Argo CD 화면에 `OutOfSync`, `Missing`으로 남지 않고, 이미지 태그가 바뀌어도 `field is immutable`이 나지 않는다.
- 순서는 sync-wave로 정한다: MySQL·SeaweedFS(0) → 마이그레이션·버킷 Job(1) → 앱(2). 앱이 `ddl-auto: validate`라서 마이그레이션보다 먼저 뜨면 기동에 실패하기 때문이다. 마이그레이션이 실패하면 앱은 새 버전으로 바뀌지 않는다.
- 마이그레이션은 여러 번 실행해도 안전하다. `run-migration.sh`가 `schema_history`에 버전과 checksum을 남기고, 이미 성공한 버전은 건너뛴다. 단, 이미 적용된 `V*.sql`을 고치면 checksum이 달라져 Job이 실패한다. 이미 적용된 파일은 고치지 말고 새 버전 파일을 추가한다.
- 첫 동기화에서 `develop`과 클러스터의 현재 상태가 다르면 그 차이가 적용된다. UI의 `Diff`로 먼저 확인한다.
- 자동 동기화를 끄고 수동으로 돌아가려면 아래 4번의 방법을 쓴다.

## 4. 앱을 등록한 뒤 알아둘 것

Argo CD는 Git에서 읽기만 한다. 저장소가 공개라 인증 정보도 주지 않았고, Git에 쓰지 않는다.

- 이미 `kubectl apply`로 올린 리소스를 Application으로 가져오면 Argo CD의 관리 표식이 없어서 처음에는 모두 `OutOfSync`로 보인다. `Sync`를 누르면 `Synced`가 된다.
- `Sync`를 누를 때 `Prune`은 체크하지 않는다. Git에 없는 리소스를 지우는 옵션이다.
- Application을 지워도 finalizer를 두지 않으면 클러스터의 리소스는 남는다.

자동 복구(selfHeal)는 처음에는 수동 동기화로 두고 필요할 때 켠다. 켜면 손으로 바꾼 것이 몇 초 안에 Git 기준으로 돌아온다.

```bash
kubectl -n argocd patch app <앱 이름> --type merge -p '{"spec":{"syncPolicy":{"automated":{"selfHeal":true}}}}'
```

끄고 수동 동기화로 돌아가려면:

```bash
kubectl -n argocd patch app <앱 이름> --type merge -p '{"spec":{"syncPolicy":null}}'
```

## 5. 되돌리기

Argo CD 전체를 지운다 (CRD도 같이 지워진다). 앱이 관리하던 리소스는 finalizer가 없으면 남는다.

```bash
kubectl delete -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.3.16/manifests/install.yaml
kubectl delete namespace argocd
```

## 6. 배포 실패 Slack 알림

동기화(Sync)가 실패하거나 앱이 `Degraded`가 되면 Slack으로 알린다. 정상 배포에는 아무것도 오지 않는다. 설정은 `notifications.yaml`(ConfigMap)에 있고, `snipy-dev` Application의 `notifications.argoproj.io/subscribe.*` annotation이 알림을 받는다고 표시한다.

웹훅 URL은 저장소에 올리지 않는다. `argocd-notifications-secret`은 Argo CD를 설치할 때 이미 만들어지는 Secret이라 Sealed Secrets로 덮어쓰지 않고, 클러스터마다 cp1에서 한 번 직접 넣는다. 채팅이나 Git에 붙이지 않는다 (CI 알림에 쓰는 웹훅과 같은 값을 써도 된다).

이미 설치된 클러스터는 아래 순서로 켠다. 새로 설치하는 클러스터는 `cp-argocd.sh`가 `notifications.yaml` 적용과 컨트롤러 기동(3번)을 해 주고, `snipy-dev-app.yaml`은 위 3번 절에서 적용하니 웹훅 입력(2번)만 하면 된다.

```bash
# 1. 알림 설정과 Application annotation 반영
kubectl apply -f /home/k8s/argocd/notifications.yaml
kubectl apply -f /home/k8s/argocd/snipy-dev-app.yaml
```

```bash
# 2. 웹훅 URL을 입력하면 화면에 보이지 않게 Secret에 넣는다
read -rsp "Slack webhook URL: " U; echo
kubectl -n argocd patch secret argocd-notifications-secret --type merge -p "{\"stringData\":{\"slack-webhook-url\":\"$U\"}}"
unset U
```

```bash
# 3. 컨트롤러를 켠다 (설치 때 0으로 줄여 둔 클러스터만)
kubectl -n argocd scale deploy argocd-notifications-controller --replicas=1
kubectl -n argocd rollout status deploy/argocd-notifications-controller
```

### 확인

일부러 Sync가 실패하는 임시 Application을 만들어 알림이 오는지 본다. Argo CD 공식 예제(`guestbook`)를 없는 네임스페이스에 배포하게 해서 "namespace not found"로 실패시킨다. 아무것도 만들어지지 않고 `snipy` 앱에는 영향이 없다. (없는 경로를 가리키면 Sync가 시작되지 않고 설정 오류로만 끝나서 `on-sync-failed`가 울리지 않는다.)

```bash
kubectl apply -f - <<'EOF'
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: notify-test
  namespace: argocd
  annotations:
    notifications.argoproj.io/subscribe.on-sync-failed.slack: ""
spec:
  project: default
  source:
    repoURL: https://github.com/argoproj/argocd-example-apps.git
    targetRevision: HEAD
    path: guestbook
  destination:
    server: https://kubernetes.default.svc
    namespace: notify-test-nonexistent
  syncPolicy:
    automated: {}
EOF
```

1~2분 안에 Slack에 `notify-test 배포(Sync) 실패`가 오면 된다. 확인이 끝나면 지운다.

```bash
kubectl -n argocd delete app notify-test
```

오지 않으면 컨트롤러 로그를 본다.

```bash
kubectl -n argocd logs deploy/argocd-notifications-controller --tail=30
```

## 주의

- `infra/argocd/`의 Application은 클라우드(AWS)용이다. `snipy-dev`는 `destination`이 `default`이고 `prune: true`, `selfHeal: true`가 켜져 있고, root app은 observability와 prod까지 끌어온다. 온프렘에서는 apply하지 않고 이 폴더의 `snipy-dev-app.yaml`을 쓴다.
- `snipy` 네임스페이스는 `infra/onprem/base`에서만 관리하고 Argo CD 앱 경로 밖에 둔다. 앱이 지워도 네임스페이스는 남는다.
- 각자 클러스터를 만들기 때문에 이 절차는 각 클러스터에서 한 번씩 한다. 설치 전에 호스트 PC와 VM의 메모리, 디스크 여유를 확인한다.
- Argo CD가 받는 이미지는 `quay.io`, `public.ecr.aws`, `ghcr.io`다. 워커에서 이 레지스트리에 접속되는지 먼저 확인한다.
