# 온프렘 롤백 절차와 리허설

dev 배포가 문제를 일으켰을 때 이전 이미지로 되돌리는 방법이다. 배포는 `develop`의 `infra/k8s/overlays/dev/kustomization.yaml`에 적힌 이미지 태그가 정하고, Argo CD가 그 내용을 클러스터에 맞춘다. 그래서 롤백도 클러스터를 직접 만지지 않고 **Git에서 태그를 이전 값으로 되돌려** 한다. 이력이 Git에 남고, 롤백 자체도 리뷰할 수 있다.

```
develop 에 태그 커밋 (CI)  ->  이상 발견  ->  git revert  ->  develop 에 반영  ->  Argo CD 동기화  ->  이전 이미지로 파드 교체
```

## 미리 갖춰져 있어야 하는 것

- 클러스터에 Argo CD가 있고(`argocd/README.md`), `snipy-dev` Application이 `infra/k8s/overlays/dev`를 가리키며 `Synced`, `Healthy` 상태다.
- `snipy` 네임스페이스에 Secret 4개가 있다 (`secrets/README.md`).
- 이미지는 GHCR에 올라가 있고 public이라 노드가 인증 없이 받는다. 롤백 대상(이전 태그)의 이미지가 지워지지 않았다.
- Argo CD 동기화 방식을 정해 두었다 (아래 "동기화 방식").

## 롤백 단위

| 대상 | 바뀌는 곳 | 태그를 바꾸는 주체 |
|---|---|---|
| 프론트 | `newTag` 1줄 (`...-frontend`) | `onprem-frontend-ci.yml`이 커밋 (`chore: 프론트 이미지 태그를 <SHA 7자리>로 갱신 [skip ci]`). 지금은 `develop` 보호 규칙 때문에 이 push가 막혀 있어서 봇 계정 설정 전에는 사람이 태그를 바꾸는 PR을 올린다 |
| 백엔드 | `newTag` 3줄 (management-service, redirect-service, db-migration. 세 이미지는 같은 SHA) | 지금은 사람이 바꾼다. CI가 바꾸게 되면 같은 방식 |

## 동기화 방식

Argo CD가 Git 변경을 언제 클러스터에 반영할지 정해야 한다.

| 방식 | 롤백 때 | 비고 |
|---|---|---|
| 수동 동기화 (기본) | 반영 후 UI에서 `Refresh`, `Sync`를 누른다 | 한 번 더 확인하고 적용한다. 리허설에서는 이쪽이 단계가 잘 보인다 |
| 자동 동기화 | Argo CD가 약 3분 안에 알아서 반영한다 | develop 자동 배포의 완료 기준과 맞는다. `prune`, `selfHeal`은 별도로 정한다 |

기다리기 싫으면 강제로 새로 고친다.

```bash
kubectl -n argocd annotate app snipy-dev argocd.argoproj.io/refresh=hard --overwrite
```

## 롤백 순서

### 1. 현재 상태를 적어 둔다

```bash
kubectl -n snipy get deploy frontend -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
kubectl -n argocd get app snipy-dev -o jsonpath='{.status.sync.status} {.status.health.status}'; echo
```

### 2. 되돌릴 커밋을 찾는다

가장 최근에 태그를 바꾼 커밋이 대상이다.

```bash
git log --oneline -5 -- infra/k8s/overlays/dev/kustomization.yaml
```

### 3. 되돌린다 (두 가지 중 하나)

**A. `git revert` (기본)**

```bash
git switch -c revert/<이슈번호>-<요약> origin/develop
git revert <태그 커밋 SHA>
git push -u origin HEAD
```

GitHub에서 해당 커밋의 `Revert` 버튼으로 PR을 만들어도 된다. `develop`에는 PR과 리뷰를 거쳐야 하므로 그만큼 시간이 더 걸린다. 급하면 권한이 있는 사람이 `develop`에 바로 push한다. 이번 리허설은 시험용 브랜치에 바로 push해서 이 시간이 들어 있지 않다 (아래 "기록").

**B. 이전 태그를 직접 지정** (revert 충돌이 날 때)

그 사이에 같은 줄을 바꾼 태그 커밋이 더 있으면 `git revert`가 충돌한다. 이때는 되돌릴 SHA를 정해서 직접 쓴다.

```bash
bash infra/onprem/bump-image-tag.sh frontend <이전 SHA>
git add infra/k8s/overlays/dev/kustomization.yaml
git commit -m "revert: 프론트 이미지 태그를 <이전 SHA 7자리>로 되돌림 (#이슈)"
```

### 4. Argo CD가 반영하게 한다

수동 동기화면 UI(`https://192.168.122.11:8443`)에서 `snipy-dev`를 열어 `Refresh`, `Sync`를 누른다. `Prune`은 체크하지 않는다. 자동 동기화면 기다린다.

### 5. 확인한다

```bash
kubectl -n argocd get app snipy-dev -o jsonpath='{.status.sync.status} {.status.health.status}'; echo
kubectl -n snipy rollout status deploy/frontend --timeout=180s
kubectl -n snipy get deploy frontend -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
kubectl -n snipy get pods -l app=frontend
```

백엔드는 `deploy/frontend` 대신 `deploy/management-service`, `deploy/redirect-service`로 같은 명령을 쓴다.

이미지가 1번에서 적어 둔 **이전 태그**로 돌아왔고, `Synced`, `Healthy`이며, 파드가 `Running`이어야 한다. 화면이 열리는지도 본다 (`http://192.168.122.21:30080`, 로그인은 터널 접속 `http://localhost:3000`에서만 된다).

## 알아둘 점

- **다음 프론트 머지가 태그를 다시 올린다.** CI는 `src/frontend`가 머지될 때마다 태그를 새 SHA로 바꾼다. 문제 코드가 `develop`에 남아 있으면 다음 머지 때 그 코드가 다시 배포된다. 근본 해결은 문제가 된 코드 커밋을 되돌리는 것이고, 태그 롤백은 그 사이를 버티는 응급 조치다.
- **DB 마이그레이션은 되돌려지지 않는다.** 백엔드를 이전 태그로 돌려도 `db-migration`이 이미 바꾼 스키마는 그대로다. 마이그레이션이 포함된 배포는 롤백 전에 스키마 호환 여부를 먼저 본다. 작성 규칙과 확인 방법은 [`src/db/README.md`의 마이그레이션 작성 규칙](../../../src/db/README.md#마이그레이션-작성-규칙)을 따른다.- **ConfigMap, Secret 변경은 이미지와 별개다.** ConfigMap은 같은 `overlays/dev`에 있으니 `git revert`로 함께 되돌아간다. Secret은 `infra/onprem/secrets`의 SealedSecret이라 이미지 롤백과 따로 적용한다.
- **Argo CD UI의 `History and Rollback`은 쓰지 않는다.** 클러스터만 되돌리고 Git은 그대로라 곧 어긋난다. 자동 동기화가 켜져 있으면 아예 막힌다. 롤백의 기준은 Git이다.
- **되돌리는 커밋은 이미지를 다시 만들지 않는다.** 되돌리는 커밋이 바꾸는 파일(`infra/k8s/**`)은 프론트 이미지 워크플로(`onprem-frontend-ci.yml`)의 `paths`에 없다. 이미지는 이미 GHCR에 있으니 다시 만들 필요도 없다. (시크릿 스캔처럼 모든 경로에서 도는 검사는 평소처럼 돈다.)

## 리허설 계획

완료 기준은 "롤백 기록"이다. 프론트와 백엔드가 함께 한다.

1. 리허설 전 상태를 적는다: 현재 태그, Application 상태.
2. 시험용 브랜치에서 이미지 태그를 GHCR에 있는 다른 태그로 바꿔 푸시하고 Argo CD로 반영한다. 새 이미지가 배포된 것을 확인한다 (이 시점이 `T0`).
3. 위 롤백 순서를 따라 되돌린다. 단계마다 시각을 적는다.
4. 이전 이미지로 복귀한 것을 확인한다.
5. 되돌린 상태에서 화면이 정상인지 본다. 필요하면 다시 최신으로 돌아간다 (롤백을 되돌리는 revert 또는 새 머지).
6. 백엔드도 같은 순서로 한다. 세 태그를 한 번에 바꾸고, `db-migration`이 다시 도는지 확인한다.

### 기록

시각은 한국 시간이다. Argo CD 반영 시각은 전체 동기화이면 `kubectl -n argocd get app snipy-dev -o jsonpath='{range .status.history[*]}{.id}{"  "}{.revision}{"  "}{.deployedAt}{"\n"}{end}'`의 `deployedAt`(UTC)에 9시간을 더한 값이고, 리소스를 골라 한 동기화(백엔드)는 아래 "리허설에서 확인된 점"의 `operationState`의 `finishedAt`에 9시간을 더한 값이다.

| 항목 | 프론트 (2026-10-22) | 백엔드 (2026-10-23) |
|---|---|---|
| 롤백 전 태그 | `edb1805a26fe…` | `e04dfaa132cf…` (management, redirect, db-migration 같은 태그) |
| 새 버전 태그(릴리스) | `691a567c6bc1…` | `3140a2a3a8a5…` |
| 롤백 후 태그 | `edb1805a26fe…` (Deployment 리비전 4) | `e04dfaa132cf…` |
| 릴리스 커밋 | `af8f5d2`, 15:44:50 | `02e419e` (10-22 18:27에 푸시, 반영은 다음 날 아침) |
| 릴리스 반영 (Argo CD) | 15:45:26 | 09:20:47 (시작 09:20:46, Deployment 두 개만 선택 동기화) |
| revert 커밋 | `035edfc`, 15:50:54 | `d9bb4c5`, 10:39:35 |
| 롤백 반영 (Argo CD) | 15:51:29 | 10:40:36 (시작 10:40:35) |
| revert 커밋 → 롤백 반영 | 35초 (푸시, Refresh, Sync 포함) | 61초 (푸시, Refresh, Sync 포함) |
| 릴리스 반영 → 롤백 반영 | 6분 3초 | 1시간 19분 49초 (릴리스 뒤 안정 상태를 확인하고 되돌림) |
| 새 파드 `Ready` 시각 | 15:51:39, 15:51:52 (파드 2개) | 릴리스: management 09:21:34, redirect 09:21:44. 롤백: 둘 다 10:41:06 |
| 반영 → 파드 `Ready` | 약 10~23초 | 릴리스 47~57초, 롤백 30초 |
| revert 커밋 → 파드 모두 `Ready` | 58초 | 91초 |
| 동기화 방식 | 수동 (Sync) | 수동 선택 동기화 (Deployment 두 개만, Job 제외) |
| 결과 | 성공. 이미지가 `edb1805` → `691a567` → `edb1805`로 바뀌었고, 파드가 이전 ReplicaSet을 재사용해 기준선과 같은 해시(`58db5b6c4`)로 돌아왔다 | 성공. 이미지가 `e04dfaa` → `3140a2a` → `e04dfaa`로 바뀌었고, 파드가 이전 ReplicaSet을 재사용해 기준선과 같은 해시(`db865445b`, `6f5699dfc7`)로 돌아왔다. 새 버전은 재시작 없이 정상 기동했다 |

리허설은 `develop`이 아니라 `rehearsal/rollback` 브랜치를 Argo CD가 바라보게 하고 진행했다. revert는 PR 없이 그 브랜치에 바로 push했다. 그래서 PR 리뷰와 머지에 드는 시간은 이 기록에 들어 있지 않다.

### 리허설에서 확인된 점

- **롤백 때 새 파드 템플릿이 만들어지지 않는다.** 같은 이미지의 이전 ReplicaSet을 다시 쓰고 새 리비전 번호를 붙인다. 그래서 `rollout history`에서 이전 번호(2)가 사라진다.
- **한 번 실패한 Job은 Argo CD가 다시 만들지 않는다.** 지우고 `Sync`로 다시 만든다.
- **끝난 Job은 `ttlSecondsAfterFinished` 때문에 10분 뒤 사라지고, Argo CD에는 `OutOfSync`로 남는다.** `db-migration`과 `seaweedfs-bucket-init`이 해당한다. `Sync`를 누르면 마이그레이션이 다시 실행되니, 프론트만 바꿀 때는 `Sync`에서 `frontend`만 선택한다. 자동 동기화를 켜기 전에 Job을 Argo CD 훅으로 바꾸거나 TTL을 없애야 한다.
- **`ignoreDifferences`의 `replicas`는 `Sync`에서 값을 보존하지 않는다.** 보존하려면 Application에 `RespectIgnoreDifferences=true`가 필요하다.
- **MySQL 첫 기동 때 `startupProbe`가 없으면 초기화가 liveness에 끊겨 root 비밀번호가 설정되지 않는다.** `overlays/dev/mysql.yaml`에 `startupProbe`를 넣었다 (리허설 브랜치에서만 적용된 상태).
- **시각은 `kubectl`의 `AGE`와 `creationTimestamp`가 아니라 Argo CD `deployedAt`과 파드 `Ready` 조건으로 잰다.** 이 리허설에서는 cp1(API 서버)의 시계가 약 43시간 느려서, 파드의 `creationTimestamp`가 이틀 전으로 찍히고 `kubectl get pods`에 `<invalid> ago`가 나왔다. 시각을 비교하기 전에 각 VM에서 `date -u`와 `timedatectl status`로 시계가 맞는지 확인한다.
- **리소스를 골라서 한 동기화(선택 동기화)는 Argo CD의 `status.history`에 남지 않는다.** 이런 동기화의 시각은 `kubectl -n argocd get app snipy-dev -o jsonpath='{.status.operationState.startedAt}{"  "}{.status.operationState.finishedAt}{"  "}{.status.operationState.syncResult.revision}{"\n"}'`로 보는데, 이 값은 다음 동기화가 실행되면 덮어써진다. 다음 `Sync`를 누르기 전에 적어 둔다.
- **`kubectl`은 클러스터가 있는 VM(cp1)에서 root로 실행한다.** 호스트 PC의 `kubectl`은 다른 클러스터 설정을 가리킬 수 있어서 연결이 거부된다.
- **백엔드는 첫 기동에 약 2분이 걸린다.** `db-migration`은 약 42초에 끝났고, `management-service`는 약 2분 6초, `redirect-service`는 약 2분 뒤에 `Ready`가 됐다. 백엔드 롤백의 소요 시간은 프론트보다 길다.

## 문제 해결

| 증상 | 원인 | 확인 / 해결 |
|---|---|---|
| `git revert`가 충돌한다 | 그 사이 같은 줄을 바꾼 태그 커밋이 있다 | 위 "B. 이전 태그를 직접 지정" |
| 파드가 `ImagePullBackOff` | 이전 태그의 이미지가 GHCR에 없거나 private이다 | 태그가 맞는지, 패키지가 public인지 확인 |
| 반영이 늦다 | Argo CD가 Git을 주기(기본 약 3분)로 확인한다 | 위의 `refresh=hard` 어노테이션 |
| 롤백했는데 곧 다시 새 버전이 된다 | 새 프론트 머지가 태그를 다시 올렸다 | 문제 코드 커밋을 먼저 되돌린다 |
