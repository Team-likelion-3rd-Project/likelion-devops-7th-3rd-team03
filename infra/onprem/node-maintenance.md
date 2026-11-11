# 온프렘 워커 노드 점검(drain) 절차

> 5.1(w2 drain)은 2026-11-03, 11-05, 11-06에 세 번 시험했고, 같은 날 개선을 적용해 한 번 더 시험했다(5.1.2). 11-09에는 워커 메모리를 늘린 뒤 한 번 더 시험했다(5.1.3). 5.2(w1 drain, DB 노드)는 계획한 시험을 하지 않았고, 11-09 메모리 증설 작업 중 본 것만 적었다. 이슈 #220, #295.

워커 노드(w1, w2)를 점검하거나 재부팅할 때 서비스 영향을 줄이는 절차다. M5 ON5의 "노드 점검 절차(drain)"이고, M6 ON6의 워커 노드 다운 시험의 기준이 된다.

- 이 문서는 **미리 계획한 점검**을 다룬다. 노드가 갑자기 죽는 경우를 흉내 내는 시험은 [ON-guide](ON-guide.md)의 F-3이고, VM 전원을 꺼서 쟀던 결과는 [node-failure-test.md](node-failure-test.md)에 있다.
- 범위는 **워커 노드**다. control-plane(cp1)은 한 대뿐이라 점검하면 API 서버가 멈춘다. 이미 떠 있는 파드는 계속 돌지만 그동안 배포, 조회, 자동 복구가 안 된다. cp1 점검은 이 문서의 범위가 아니다.

## 1. 먼저 알아야 할 것

### 1.1 무엇이 어디에 얼마나 떠 있나

`infra/k8s/overlays/dev` 매니페스트 기준이다. 어느 노드에 있는지는 바뀌므로 점검 전에 항상 다시 확인한다.

```bash
kubectl -n snipy get pods -o wide
```

| 대상 | 종류·개수 | 노드를 비우면 |
|---|---|---|
| management-service | Rollout, 2개. PDB 없음 | 다른 노드로 옮겨 가지만 잠깐 끊긴다 |
| redirect-service | Rollout, 2개. PDB 없음 | 같음. 두 파드가 같은 노드에 있으면 **동시에** 내려가 리다이렉트가 잠깐 끊긴다 |
| frontend | Deployment, 2개. PDB `minAvailable: 1`, 노드 분산 설정 | 한 번에 1개씩 순서대로 옮겨 간다 |
| ingress-nginx-controller | Deployment, 1개 (2026-11-03 기준 w2에 있었음). PDB 없음 | 노드를 비우면 새 파드가 뜰 때까지 **프론트의 진입점이 없다** (약 20초 중단, 5.1) |
| redis | Deployment, 1개. 볼륨 없음 | 옮겨 가지만 **메모리에 있던 값(오늘 실시간 클릭 수 등)은 사라진다** |
| `mysql-0` | StatefulSet, 1개. local-path 볼륨 5Gi | **다른 노드로 못 옮겨 간다** (1.2) |
| `seaweedfs-0` | StatefulSet, 1개. local-path 볼륨 | **다른 노드로 못 옮겨 간다** (1.2) |
| `click-stats-batch` | CronJob (일별 집계) | 도는 중에 노드를 비우면 그 실행이 끊긴다 |

2026-11-05에 확인한 배치는 아래와 같다. 배치는 바뀌므로 이 표를 믿지 말고 점검 전에 다시 본다.

| 노드 | 올라가 있던 것 |
|---|---|
| k8s-w1 | `mysql-0`, `seaweedfs-0`, `redis` |
| k8s-w2 | frontend 2, management 2, redirect 2, Argo CD(application-controller, repo-server, server) |

(w1이 cordon된 채로 있어서 앱 파드가 모두 w2에 몰려 있었다.)

볼륨 때문에 두 StatefulSet이 어느 노드에 묶였는지도 매번 확인한다. 2026-11-05에는 둘 다 w1이었고 reclaim 정책은 `Delete`였다.

```bash
kubectl get pv -o custom-columns=PV:.metadata.name,CLAIM:.spec.claimRef.name,NODE:.spec.nodeAffinity.required.nodeSelectorTerms[0].matchExpressions[0].values[0],RECLAIM:.spec.persistentVolumeReclaimPolicy
```

### 1.2 이동할 수 없는 파드 (local-path 볼륨)

`mysql-0`과 `seaweedfs-0`은 local-path 볼륨(RWO)을 쓴다. 볼륨 데이터는 **그 노드의 디스크**에 있어서 다른 노드에서는 마운트할 수 없다.
그 노드를 drain하면 이 파드들은 쫓겨난 뒤 **노드가 다시 돌아올 때까지 Pending**이다. 그동안 DB와 SeaweedFS를 쓰는 기능이 멈춘다.
데이터는 디스크에 남아 있으니 **PVC를 지우지 않는다.** (reclaim 정책이 Delete라서 PVC를 지우면 데이터도 사라진다.)

### 1.3 PDB(파드 중단 예산)

- `frontend`: `minAvailable: 1`, 파드 2개. 한 번에 1개만 내려가므로 drain이 순서대로 진행된다.
- management, redirect: **PDB가 없다.** 2개가 서로 다른 노드에 있으면 각 노드에서 1개씩만 옮겨 가서 잠깐 용량이 절반이 된다. 2개가 같은 노드에 있으면 그 노드를 비울 때 둘이 동시에 내려가 응답이 끊긴다.

### 1.4 용량

워커가 2대뿐이라 한쪽을 비우면 모든 파드가 남은 쪽으로 몰린다. CPU와 메모리가 부족하면 파드가 Pending이 될 수 있다.

```bash
kubectl top nodes
```

이 명령이 에러를 내면 metrics-server에 문제가 있는 것이다(HPA의 CPU 값이 `<unknown>`인 것과 같은 원인일 수 있다).

스케줄러는 사용량이 아니라 **requests** 기준으로 배치한다. 옮겨 갈 파드의 requests가 남는 노드의 빈 자리보다 크면 Pending이 된다.

```bash
kubectl describe nodes | grep -A8 "Allocated resources"
kubectl get nodes -o custom-columns=NODE:.metadata.name,MEM:.status.allocatable.memory
```

2026-11-05 값(메모리): allocatable은 w1 약 3170Mi, w2 약 3250Mi이고, requests는 w1 1122Mi(35%), w2 2176Mi(66%)다. 그래서 **w2를 비우면** 옮겨 갈 약 2176Mi를 w1에 더해 약 3298Mi가 되어 w1 allocatable을 약 128Mi 넘는다. 그래서 파드 한두 개가 `Insufficient memory`로 Pending이 될 것으로 예상했고, 실제로 2026-11-05 w2 drain에서 management 1개가 Pending이 되었다 (5.1). 몰린 직후에는 w1의 CPU도 87%까지 올랐다.

2026-11-09에 두 워커의 VM 메모리를 늘렸다(#295). Capacity는 w1이 3349088Ki에서 5552724Ki, w2가 5552760Ki가 되었고 allocatable은 둘 다 약 5322Mi다. 늘리기 전에는 requests 합계가 3388Mi(w1 1692Mi, w2 1696Mi)로, 한 노드의 allocatable(약 3170Mi)을 약 218Mi 넘었다. 그래서 한쪽을 비우면 그만큼은 Pending이 될 수 있었다. 점검 전에는 requests 합계가 남는 노드의 allocatable 안에 들어오는지 본다.

## 2. 점검 전 준비

**가장 먼저** 노드 상태를 본다. 비우지 않을 노드가 모두 `Ready`여야 하고 `SchedulingDisabled`가 붙어 있으면 안 된다. 전에 걸어 둔 cordon을 잊고 있으면 쫓겨난 파드가 갈 곳이 없어 서비스 전체가 멈춘다 (2026-11-05에 실제로 겪었다).

```bash
kubectl get nodes
```

1. **앱이 정상인지 확인한다.** Argo CD가 `Degraded`이거나 `OutOfSync`이면 먼저 원인을 해결한다.
   ```bash
   kubectl -n argocd get applications
   ```
2. **집계 배치가 돌고 있지 않은지 본다.** 돌고 있으면 끝난 뒤에 시작한다.
   ```bash
   kubectl -n snipy get jobs
   ```
3. **백업을 만든다.** DB 노드(대개 w1)를 비울 때는 필수, 그 외에도 권장한다.
   ```bash
   bash /home/k8s/backup/etcd-backup.sh
   bash /home/k8s/backup/mysql-backup.sh
   ```
   S3 업로드(#221)가 설치된 뒤에는 `s3-upload.sh`도 실행해 최신 백업이 올라가 있게 한다 ([backup/README.md](backup/README.md)).
4. **서비스 응답을 지켜볼 창을 하나 더 연다.** 1초마다 상태 코드를 찍는다. 주소는 환경에 맞게 바꾼다.
   ```bash
   while true; do printf "%s " "$(date -u +%T)"; curl -s -o /dev/null -w "%{http_code}\n" --max-time 2 http://<cp1-ip>:30080/; sleep 1; done
   ```
5. **배포가 진행 중이 아닌지 본다.** 새 버전 롤아웃이 한창일 때 노드를 비우면 한 노드에 몰린 파드 위에 카나리 파드까지 얹혀 포화된다(5.1.3). `develop`에 머지가 들어가면 CI 봇이 이미지 태그를 갱신하고 Argo CD가 롤아웃을 시작하므로, 점검 직전에 확인하고 점검이 끝날 때까지 머지를 미룬다.
   ```bash
   kubectl -n snipy get rollout
   ```
   DESIRED, CURRENT, UP-TO-DATE, AVAILABLE이 모두 같아야 한다.

## 3. 절차

`<node>`는 `k8s-w1` 또는 `k8s-w2`다.

### 3.1 새 파드가 올라오지 않게 막는다 (cordon)
```bash
kubectl cordon <node>
kubectl get nodes     # STATUS에 SchedulingDisabled가 붙는다
```

### 3.2 파드를 비운다 (drain)
```bash
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data --timeout=300s
```
- `--ignore-daemonsets`: DaemonSet(네트워크 에이전트 등)은 원래 노드에 남긴다.
- `--delete-emptydir-data`: `emptyDir` 볼륨 데이터는 사라진다(임시 데이터라 괜찮다).
- `--force`는 쓰지 않는다. 컨트롤러 없는 파드를 강제로 지우는 옵션이라 무엇을 지우는지 모른 채 쓰면 위험하다.
- drain이 `--timeout`을 넘겨 실패해도 **노드는 cordon 상태로 남는다.** 원인을 해결하거나 `kubectl uncordon <node>`로 직접 되돌린다.

### 3.3 점검 작업과 재부팅
IP와 호스트 이름의 짝은 환경마다 다를 수 있다 (2026-11-05 클러스터는 ON-guide와 w1, w2의 IP가 반대였다). 접속할 IP는 `kubectl get nodes -o wide`의 `INTERNAL-IP`로 정하고, **재부팅 전에 `hostname`이 점검 대상과 같은지 확인한다.**
```bash
ssh k8s@<INTERNAL-IP>
hostname          # 점검 대상(k8s-w1 또는 k8s-w2)과 같을 때만 다음으로 간다
sudo reboot
```
재부팅이 끝났는지는 `uptime`으로 확인한다. VM이 빨리 돌아오면 `kubectl get nodes -w`에서 `NotReady`가 한 번도 안 보일 수 있다 (표시되기까지 약 40초가 걸린다).

### 3.4 되돌린다 (uncordon)
노드가 `Ready`가 된 뒤에 실행한다.
```bash
kubectl get nodes
kubectl uncordon <node>
```

### 3.5 확인
```bash
kubectl get nodes
kubectl -n snipy get pods -o wide
kubectl -n argocd get applications
```
`mysql-0`이 `Running`이고 Argo CD가 `Healthy`인지 본다. `kubectl get nodes`에 `SchedulingDisabled`가 남아 있지 않은지도 본다. uncordon만으로는 파드가 알아서 되돌아오지 않는다. 파드는 새로 만들어질 때 배치되므로 한쪽에 몰려 있을 수 있다.

한쪽 워커에 파드가 몰려 있으면 그 워커의 부하를 본다. 워커 VM에 접속해서(`ssh k8s@<워커 IP>`) 실행한다.

```bash
top -bn1 | head -5
```

`%Cpu(s)`의 `sy`(커널 CPU)가 높고(시험 때 70% 넘게 나왔다) `avail Mem`이 500MB 안팎이면 메모리가 부족해 커널이 메모리를 회수하느라 바쁜 상태다. 이때는 kubelet과 `calico-node`의 점검이 느려져 파드가 `Ready`와 `NotReady`를 오간다. 몰린 쪽의 JVM 파드(management, redirect) 하나를 지워 다른 워커로 옮긴다. 남은 파드가 있어 서비스는 끊기지 않는다.

```bash
kubectl -n snipy get pods -o wide | grep -E "management|redirect"
kubectl -n snipy delete pod <몰린 워커에 있는 management 파드 하나>
```

새 파드가 다른 워커에서 `1/1`이 되면 `top`을 다시 본다(5.1.2에서 이렇게 해서 풀렸다). 새 파드가 같은 워커로 가면 그 워커를 `cordon`한 뒤 지우고 `uncordon`한다.

## 4. 문제가 생겼을 때

| 증상 | 원인 | 조치 |
|---|---|---|
| drain이 멈춘다 | PDB 때문에 더 내릴 수 없다 | `kubectl -n snipy get pdb`로 `ALLOWED DISRUPTIONS`가 0인 것을 찾는다. 다른 파드가 Ready가 될 때까지 기다린다. 강제로 지우지 않는다. |
| `mysql-0`이 Pending | DB 노드가 cordon, drain 상태라 볼륨 노드에 못 간다 | 정상이다. 그 노드를 `uncordon`하면 돌아온다. |
| 파드가 Pending (자원 부족) | 남은 워커의 CPU, 메모리가 부족 | `kubectl describe pod`의 Events를 본다. 점검 대상 노드를 `uncordon`해서 되돌린다. |
| 재부팅 후 앱이 안 뜬다 | VM 재기동 직후 기동 순서 문제 | #167, #178 참고. Argo CD 상태와 `kubectl -n snipy get pods`로 확인한다. |
| 중간에 취소하고 싶다 | - | `kubectl uncordon <node>`로 되돌린다. 이미 쫓겨난 파드는 다시 배치될 때까지 기다린다. |
| 쫓겨난 파드가 전부 Pending (`0/3 nodes are available ... unschedulable`) | 다른 워커도 cordon 상태라 갈 곳이 없다 | `kubectl get nodes`로 `SchedulingDisabled`를 찾는다. 의도한 cordon이 아니면 그 노드를 `uncordon`한다. 점검 대상 노드를 `uncordon`해도 파드가 돌아온다. |
| drain이 5분 뒤 `global timeout reached`로 실패 | 쫓겨난 파드의 새 파드가 Ready가 되지 못해 PDB가 다음 파드를 막는다 | 새 파드의 `kubectl describe pod` Events를 본다. 서비스가 끊겼거나 해결이 어렵다면 `kubectl uncordon <node>`로 되돌린다. 노드는 cordon 상태로 남아 있다. |
| 프론트만 약 20초 접속이 안 된다 (redirect는 정상) | ingress-nginx-controller가 1개뿐이고 비운 노드에 있었다 | 정상 동작이다. 점검 전에 `kubectl -n ingress-nginx get pods -o wide`로 어느 노드에 있는지 보고, 이 시간을 감수할지 정한다. 개선 후보는 6절 |
| drain 뒤 Argo CD가 몇 분째 `0/1`이고 재시작한다 | 파드가 한 노드로 몰려 그 노드가 포화되어(메모리 부족, 커널 CPU 증가) 시작 점검에 실패한다 | 5.1.1의 복구 절차를 따른다. 기다려도 저절로 풀리지 않는다(3차 시험에서 측정을 끝낸 뒤에도 풀리지 않아 직접 복구했다). |
| `snipy-dev`가 `OutOfSync`에서 멈추고 `db-migration` Job이 실패한다 | 이미 적용된 `V*.sql` 파일이 바뀌어 DB의 체크섬과 맞지 않는다 (Job 로그에 "체크섬 불일치") | `src/db/README.md` 절차를 따른다. 백업을 만든 뒤 `schema_history`의 해당 버전 체크섬을 새 파일 값으로 갱신하고 Argo CD에서 Sync한다. 2026-11-06 V12(#250)에서 발생했다. |

## 5. 시험 기록

5.1(w2 drain)은 시험했고 5.2(w1 drain)는 계획한 시험은 하지 않았다(11-09에 관찰한 것만 적었다). 시험 전에는 앱이 `Healthy`여야 한다(Argo CD 동기화 문제 #224, PR #225 머지 후 권장).

### 5.0 시험 방법

터미널 세 개를 쓴다. 시각은 UTC로 찍어 서로 맞춘다.

| 터미널 | 무엇을 | 명령 |
|---|---|---|
| 1 | 응답 상태 코드 | 2절 4번의 `while` 반복문. 끝나면 `Ctrl+C` |
| 2 | 파드 이동 관찰 | `kubectl -n snipy get pods -o wide -w` |
| 3 | 실제 작업 | `date -u +%T; time kubectl drain <node> --ignore-daemonsets --delete-emptydir-data --timeout=300s` |

1차(11-03)와 3차(11-06) 시험은 프론트 NodePort 30080과 redirect NodePort 30081을 각각 1초 간격으로 감시했고, 노드·파드 상태가 바뀐 시각과 노드 자원(30초 간격 `kubectl top nodes`)도 함께 기록했다.

기록할 시각: ① drain 시작 ② drain 끝 ③ 터미널 1에서 200이 아닌 줄이 처음/마지막으로 나온 때 ④ 터미널 2에서 새 파드가 `Running`이 된 때 ⑤ `uncordon` 시각과 정상화 시각.

### 5.1 w2 drain (DB를 건드리지 않는 쪽)
시험 전에 2절의 `kubectl get nodes`로 `SchedulingDisabled`가 없는 것을 확인하고, w2에 `mysql-0`, `seaweedfs-0`이 없는지 1.1의 명령으로 확인한다.

세 번 시험했다. 1차와 3차는 응답 중단을 측정했고, 2차는 측정하지 못했다.

| 항목 | 1차 2026-11-03 | 2차 2026-11-05 | 3차 2026-11-06 |
|---|---|---|---|
| 시험 일시 | 07:47:55Z 시작 (한국 16:47) | 약 02:07 UTC 시작 | 01:42:02Z 시작 (한국 10:42) |
| 시험 전 배치 | w2에 ingress-nginx-controller 1개가 있었다 (나머지 배치는 기록하지 않음) | w2에 frontend 2, management 2, redirect 2, Argo CD 3. w1에 `mysql-0`, `seaweedfs-0`, `redis` | w2에 frontend 1, management 1, redirect 1, ingress-nginx-controller 1, Argo CD 6, argo-rollouts, sealed-secrets, metrics-server, calico-apiserver. w1에 `mysql-0`, `seaweedfs-0`, `redis`, frontend 1, management 1, redirect 1. 시험 전 w1·w2 메모리 64%, CPU 2~6% |
| drain 시작 → 완료 | **약 23초** (07:47:55 cordon, 07:48:18 완료) | **33초** (`real 0m33.028s`) | **14.3초** (`real 0m14.259s`, 01:42:02 → 01:42:17) |
| 쫓겨난 것 | 19개 (앱, 시스템 파드, 끝난 Job 파드) | frontend 2, management 2, redirect 2, Argo CD 3 (+ 끝난 Job 파드) | 16개: frontend 1, management 1, redirect 1, ingress-nginx 1, Argo CD 6, argo-rollouts, sealed-secrets, metrics-server, calico-apiserver 각 1, 끝난 k6 Job 2 |
| 프론트 응답 (30080) | **약 20초 중단.** 07:48:06~25에 `000`(연결 실패), 07:48:26부터 `200` | 측정하지 못했다 | **약 22초 중단.** 01:42:04~25에 `000`(연결 실패), 01:42:26부터 `200` |
| redirect 응답 (30081) | **중단 없음** (내내 `404`, 서버 응답 정상) | 측정하지 못했다 | **중단 없음** (내내 `404`, 서버 응답 정상) |
| 프론트 중단 원인 | ingress-nginx-controller 1개가 w2에 있었고, 쫓겨난 뒤 w1에서 새로 뜰 때까지 진입점이 없었다 (프론트 파드는 w1에 하나 남아 있었다). ingress 로그까지는 보지 않았다 | - | ingress-nginx-controller 1개가 w2에 있었다. 새 파드가 01:42:03 Pending → 01:42:13 `0/1 Running` → 01:42:27 `1/1`이 되어 진입점이 돌아왔다 (프론트 복구 01:42:26과 맞음) |
| PDB가 drain을 막았는가 | 막지 않음 | frontend만 한 개씩 쫓겨났다. 두 번째 frontend는 첫 번째의 새 파드가 w1에서 Ready가 될 때까지 약 25초 기다렸다. management, redirect, Argo CD는 PDB가 없어 거의 동시에 쫓겨났다 | 막지 않음 (frontend, management, redirect 모두 다른 파드 1개가 w1에 남아 있었다) |
| 자원 부족(Pending) | management 1개가 약 2분 Pending (w1 메모리 requests 87%) | management 1개가 Pending: `0/3 nodes are available: 1 Insufficient memory, 1 node(s) had untolerated taint {node-role.kubernetes.io/control-plane: }, 1 node(s) were unschedulable` | management 1개가 01:42:06부터 **14분 38초 동안 Pending**, w2를 uncordon한 01:56:44에 풀렸다. `0/3 nodes are available: 1 Insufficient memory, 1 node(s) had untolerated taint {node-role.kubernetes.io/control-plane: }, 1 node(s) were unschedulable`. |
| 파드가 w1에서 Running이 된 시간 | 측정하지 못했다 | drain 약 4분 뒤 frontend 2, redirect 2, management 1이 Running. 정확한 시각은 측정하지 못했다 | frontend `1/1` 01:42:11 (8초), ingress-nginx `1/1` 01:42:27, redirect `2/2` 01:44:26 (2분 23초), management `1/1` 01:57:24 (w2에서, uncordon 후) |
| w1 부하 | 13분 뒤 CPU 75%, 메모리 75% (w2는 CPU 1%) | 파드가 몰린 직후 CPU 87%, 메모리 75% | 시험 전 CPU 2~6%, 메모리 64% → 01:42:40부터 **CPU 83~90%, 메모리 70~77%가 02:05까지 계속** (22분 이상, Argo CD 파드를 w2로 옮긴 뒤에도 내려가지 않음). `top`: 사용자 CPU 5.8%, 시스템(커널) CPU 74.2%, 소프트웨어 인터럽트(si) 19.8%, 유휴 0.2%, free 메모리 175MB(avail 519MB), 가장 바쁜 프로세스 `kswapd0`. w1 지표가 `<unknown>`인 구간 있음 |
| Argo CD | 13분 뒤에도 application-controller `0/1`, repo-server `0/1`(재시작 5회), server `CrashLoopBackOff`(재시작 13회) | drain 5분 뒤에도 세 파드가 `0/1`이었고 repo-server, server는 2번 재시작했다 | 01:42:03 쫓겨난 뒤 w1에서 시작 점검 실패를 반복 (Events: ConfigMap·Secret 마운트 `timed out waiting for the condition`, `/healthz` 접속 `connection refused`로 liveness probe 실패). argocd-server는 `0/1`로 재시작을 반복했고, repo-server는 `CrashLoopBackOff`가 되었고, controller는 `1/1`과 `0/1`을 오갔다 (재시작 횟수는 기록하지 않음) |
| 재부팅 | 하지 않음 | w2 재부팅 후 1분 안에 복귀. `NotReady`는 watch에 보이지 않았다 | 하지 않음 |
| uncordon 후 | management만 w2로 돌아왔고 나머지는 w1에 남았다. 자동 재균형은 없다 | 02:22:17 uncordon. Pending이던 management 1개만 w2에서 Running이 되었고, 나머지 파드는 w1에 그대로 남았다 (파드가 알아서 되돌아오지 않는다) | 01:56:44 w2 uncordon. Pending이던 management만 w2에서 Running이 되었고 나머지는 w1에 그대로 남았다. 이후에도 w1 CPU는 85% 안팎 |
| 복구 | - | - | 01:56:44 w2 uncordon → 02:01:14 w1 cordon → 02:01:16 Argo CD 파드 3개(controller, repo-server, server) 삭제 → w2에서 다시 생성: repo-server `1/1` 02:01:28(12초), server 02:01:48(32초), controller 02:02:44(88초, 이전 파드가 끝나길 기다린 시간 포함) → 02:05:13 w1 uncordon. 방법은 5.1.1 |
| 그 밖의 영향 | - | - | w1으로 몰린 다른 시스템 파드도 불안정했다: notifications-controller `CrashLoopBackOff`(01:48:09, 01:49:46 회복), sealed-secrets-controller `CrashLoopBackOff`(01:48:56 이후 반복, 02:02:08에도), argo-rollouts `CrashLoopBackOff`(01:56:41, 02:04:38에도). snipy의 `redis`는 Ready가 1/1과 0/1을 반복했다(01:43:58~02:01:37). 시험 약 1시간 뒤에도 두 파드는 w1에서 계속 재시작 중이었다(sealed-secrets-controller 12회, argo-rollouts 11회). 5.1.1 방식으로 지워 w2에서 다시 만들자 둘 다 바로 `1/1`이 되었다 |

**Argo CD가 멈춘 원인.** 3차 시험에서 직접 관찰한 내용이다. 파드가 w1 한 곳에 몰리면서 w1의 CPU가 83~90%로 올라 내려오지 않았다(시험 전 2~6%). w1에서 본 `top`은 사용자 CPU 5.8%, 시스템(커널) CPU 74.2%, 소프트웨어 인터럽트(si) 19.8%, 유휴 0.2%였고, free 메모리 175MB(avail 519MB, swap 없음)에서 가장 바쁜 프로세스가 `kswapd0`였다. 메모리가 바닥나 커널이 메모리를 회수하느라 CPU를 쓰고, 그 때문에 kubelet·containerd 응답이 늦어져 마운트 `timed out`과 `/healthz` 연결 거부로 liveness probe가 실패한 것으로 보인다. 같은 Argo CD 파드를 w2에 다시 만들었을 때는 12~88초 안에 모두 `1/1`이 되었다. 다만 이는 한 순간의 `top`과 Events를 근거로 한 판단이고 원인을 증명한 것은 아니다. Argo CD 파드에는 resource requests가 없어(BestEffort) 스케줄러가 w1의 부하를 반영하지 못하고 w1에 몰아 둔다. 컨트롤러가 멈춘 동안은 `Synced/Healthy`로 보이는 값이 옛 상태일 수 있고 develop 머지가 dev에 자동 반영되지 않는다. snipy 앱 파드는 그동안에도 `Running`이었다.

**한계**
- 워커가 2대이고 노드당 메모리가 약 3.2GB(2차 시험 때 w1 requests 87%)라서, **한쪽을 비우면 남은 쪽이 한계에 몰린다.** 3차 시험에서 w1의 CPU 83~90%가 22분 이상 내려가지 않았다.
- ingress controller가 1개라서 그 노드를 점검하면 프론트가 약 20초 끊긴다(1차 20초, 3차 22초). redirect는 별도 NodePort(30081)이고 파드가 두 노드에 나뉘어 있어 영향이 없었다.
- Argo CD, ingress, sealed-secrets 같은 시스템 파드가 함께 이동하고 자동으로 돌아오지 않아서, 점검 뒤 한쪽으로 몰린 상태가 남는다. 몰린 쪽이 포화되면 Argo CD가 20분 가까이 멈춘다.
- 2차의 응답 중단은 측정하지 못했다.
- (11-09 추가) 노드 메모리를 약 5.3GB로 늘린 뒤에는 5.1.3처럼 몇 초 중단으로 끝났지만, 한 노드에 모든 파드가 몰린 상태에서 배포가 겹치면 load가 51까지 올랐다. vCPU는 2개 그대로다.

#### 5.1.1 Argo CD가 멈췄을 때 복구 (3차 시험에서 실행)

파드가 w1로 몰려 Argo CD가 `0/1`이거나 `CrashLoopBackOff`를 반복할 때, w1에 새 파드가 못 오게 막고 Argo CD 파드를 지워 비어 있는 w2에 다시 만들게 한다. 순서를 지킨다.

1. w2를 되돌린다(Pending이던 파드가 올라간다).
   ```bash
   kubectl uncordon k8s-w2
   ```
2. w1에 새 파드가 못 오게 막는다. 떠 있는 파드(`mysql-0` 등)는 내쫓지 않는다.
   ```bash
   kubectl cordon k8s-w1
   ```
3. 멈춘 Argo CD 파드를 지운다. 컨트롤러가 다시 만들고, w1이 막혀 있으므로 w2에 만들어진다.
   ```bash
   kubectl -n argocd delete pod argocd-application-controller-0 <repo-server 파드> <server 파드>
   ```
4. `kubectl -n argocd get pods -o wide`에서 `NODE`가 `k8s-w2`이고 모두 `1/1`인지 본다. controller는 StatefulSet이라 이전 파드가 끝나야 새 파드가 뜨므로 1~2분 걸린다.
5. 4번이 끝난 **뒤에** w1을 풀어 준다. 먼저 풀면 새 파드가 다시 w1로 몰릴 수 있다.
   ```bash
   kubectl uncordon k8s-w1
   ```

이 방법은 지운 파드만 옮긴다. 3차 시험에서는 sealed-secrets-controller와 argo-rollouts도 w1에서 계속 재시작했고, 같은 방식으로 지워서 w2로 옮기자 바로 `1/1`이 되었다. 파드는 라벨 선택(`-l`)이 환경마다 맞지 않을 수 있으니 `kubectl get pods -A -o wide`로 이름을 확인해 이름으로 지운다.

#### 5.1.2 개선 후 시험 (2026-11-06, ingress 2개 + PDB 적용)

3차 시험 뒤에 ingress-nginx-controller를 2개로 늘려 서로 다른 워커에 두고, management·redirect에 PDB(`minAvailable: 1`)를 적용한 뒤 같은 방법으로 w2를 한 번 더 비웠다. 측정 방법과 절차는 3차와 같다. Argo CD에 resource requests(controller 192Mi 등)도 함께 적용했는데, 아래 결과대로 효과를 확인하지 못해 개선안에서 뺐다.

시작 배치: w2에 ingress 1, frontend 1, management 1, redirect 1, Argo CD 3(controller, redis, notifications), argo-rollouts, sealed-secrets. w1에 ingress 1, frontend 1, management 1, redirect 1, `mysql-0`, `seaweedfs-0`, `redis`, Argo CD 2(repo-server, server). 시험 전 메모리 requests는 w1 73%, w2 45%.

| 항목 | 3차 (개선 전) | 개선 후 |
|---|---|---|
| drain 시작 → 완료 | 14.3초 | 13.3초 (02:31:06 → 02:31:19) |
| 프론트 응답 (30080) | 약 22초 중단 | **약 1초 중단** (02:31:10 `000` → 02:31:11 `200`) |
| redirect 응답 (30081) | 중단 없음 | **1분 40초 중단** (02:40:26 `000` → 02:42:06 `404`) |
| management | 14분 38초 Pending | Pending 없음. 두 파드가 02:40:35~02:42:00 사이 약 80초 `0/1` (`/api` 영향은 측정하지 못함) |
| redirect 두 번째 파드 | w1에서 2분 23초 만에 `2/2` | w1에 자리가 없어 02:31:08부터 **10분 23초 Pending**, w2 uncordon(02:41:31) 뒤 `2/2` 02:43:01 |
| Argo CD controller | `1/1`과 `0/1`을 오감 | **10분 30초 Pending** (02:31:10 → 02:41:31), `1/1` 02:41:40 |
| Argo CD server, repo-server | 재시작 반복 | `0/1`을 오감. server는 02:36:26~02:43:12 `0/1`, 재시작 3회 |
| `mysql-0` | 기록 없음 | `0/1`이 02:33:34~48, 02:39:59~02:41:26(약 87초) |
| w1 CPU | 83~90%가 22분 이상 | 77~89%가 마지막 기록(02:45:58)까지 (w2는 1~3%). 지표가 `<unknown>`인 구간 있음 |
| 복구 | w2 uncordon → w1 cordon → Argo 파드 삭제 → w1 uncordon | 같은 순서. repo-server `1/1` 02:43:33(약 11초), server `1/1` 02:43:53(약 31초), w1 uncordon 02:44:25. sealed-secrets도 한 번 더 옮김(02:44:56 cordon → `1/1` 02:44:59 → 02:46:04 uncordon) |

읽는 법
- **좋아진 것: 프론트 중단 22초 → 1초.** w2를 비워도 w1의 ingress 컨트롤러가 계속 요청을 받았다.
- **나빠진 것: redirect가 끊겼다.** w1에 남은 redirect 파드가 `2/2`에서 `1/2`로 떨어졌는데(02:40:26), 두 번째 파드가 Pending이라 받아 줄 파드가 없었다. w1 메모리 requests가 96%였다. Argo CD requests와 ingress 파드가 자리를 차지한 영향일 수 있으나, 3차와 시작 배치가 달라서 원인을 하나로 확정하지는 못했다.
- 그대로인 것: w1 CPU 포화, Argo CD 불안정, `redis`·`mysql-0`의 Ready 흔들림. 한쪽 워커를 비우면 모든 파드가 w1로 몰리는 구조 때문이다. 이번 개선은 이것을 풀지 못한다.
- w1 부하(`top`): uncordon 약 10분 뒤(02:50 전후로 추정, 시각은 기록하지 않음) `sy` 73.6%, 소프트웨어 인터럽트 20.7%, 유휴 0.4%, free 174MB, avail 502MB로 3차와 거의 같았다. 앱 프로세스는 거의 보이지 않고 `snapd`, `kubelet`, `kswapd0`, `containerd`가 CPU를 썼다. 이 시점에 w1의 `calico-node`는 `0/1`이었고 liveness·readiness 점검이 시간 초과로 실패했다(재시작 38회, 로그에 `InternalDataplaneMainLoop` timed out).
- 부하를 덜자 풀렸다: w1에 몰린 management 하나를 지워 w2로 옮긴 뒤 `calico-node`가 `1/1`이 되었고, 04:18(약 1시간 20분 뒤) w1은 `sy` 8.3%, 유휴 91.7%, avail 760MB였다. 옮긴 시각과 `calico-node` 회복 시각이 정확히 맞는지는 기록하지 않아서, 옮긴 것이 원인이었다고 단정하지는 못한다.
- 측정하지 못한 것: `/api` 응답, `snapd`가 CPU를 쓴 이유(메모리 부족의 결과인지 원인인지).

#### 5.1.3 메모리를 늘린 뒤 시험 (2026-11-09, #295)

두 워커 VM의 메모리를 늘린 뒤(Capacity 약 5.3GB, allocatable 약 5322Mi) w2를 비웠다. 측정 방법은 5.0과 같고, 1초 간격 응답은 cp1 주소(`192.168.122.11`)의 프론트(30080), `/api/workspaces`, redirect(30081)와 w1 주소(`192.168.122.21`)의 프론트(30080)로 봤다. **옮긴 파드가 5.1.2보다 적고 Argo CD, ingress가 w2에 없어서 같은 조건의 비교가 아니다.** 메모리 증설의 효과라고 단정하지 않는다.

시작 배치(07:24:31 `state.log`): w2에 frontend 1, management 1, redirect 1, argo-rollouts, calico-typha가 있었다. w2에서 쫓겨난 것은 이 5개다. Argo CD, ingress-nginx, `mysql-0`, `seaweedfs-0`, `redis`는 w2에 없었다.

| 항목 | 5.1.2 (4차, 노드 메모리 약 3.2GB) | 5.1.3 (노드 메모리 약 5.3GB) |
|---|---|---|
| drain 시작 | 02:31:06 | 07:24:38 (`state.log`에 `k8s-w2 Ready,SchedulingDisabled`) |
| 쫓겨난 것 | 시작 배치 기준 9개 이상 (Argo CD 3, ingress 포함) | 5개 |
| 프론트 응답 (cp1:30080) | 약 1초 중단 | 약 5초 중단 (07:24:39 `000` → 07:24:44 `200`) |
| `/api` 응답 | 측정하지 못함 | 약 3초 중단 (07:24:41 `000` → 07:24:44 `401`) |
| redirect 응답 (30081) | 1분 40초 중단 | 약 1초 중단 (07:24:43 `000` → 07:24:44 `404`) |
| w1 주소의 프론트 | - | 중단 없음 |
| Pending | redirect 두 번째 파드 10분 23초, Argo CD controller 10분 30초 | 없음 |
| 새 파드가 Ready | redirect 두 번째 파드는 w2 uncordon 뒤 02:43:01 | frontend 07:25:30, argo-rollouts 07:25:37, management 07:26:23, redirect 07:27:51 (drain 뒤 3분 13초) |
| w1 CPU (`kubectl top`) | 77~89%가 마지막 기록(02:45:58)까지 | 6%(07:24:30) → 87%(07:25:01) → 91%(07:25:33) → 92%(07:26:04, 최대) → 70%(07:26:35) → 15%(07:28:07). 87% 이상은 약 1~1분 30초 |
| w1 메모리 | requests 96% | `top` 61%(3270Mi) → 최대 72%(3882Mi, 07:25:33) → 67%(3608Mi, 07:32:12) |
| w1 available (`free -h`) | 502MB (`top`, uncordon 약 10분 뒤) | 1.4Gi (07:28:15, 07:30:07) |

응답은 1초 간격이지만 2초 제한시간(`--max-time 2`)을 쓰고 상태가 바뀔 때만 기록하므로, 중단 길이에는 ±2초 정도의 오차가 있다.

읽는 법
- 서비스는 몇 초 끊긴 뒤 돌아왔고 Pending 파드가 없었다. #295의 완료 조건 중 "남은 워커의 파드가 모두 Running/Ready", "프론트·redirect가 수 초 이내", "available 약 1GB 이상"은 만족했다. "커널 CPU(`sy`)가 포화되지 않는다"는 `sy`를 재지 않아 확인하지 못했다. 총 CPU는 최대 92%였다.
- 끊긴 몇 초의 원인은 확인하지 못했다. w1 주소로 본 프론트는 끊기지 않았고, 끊긴 것은 cp1 주소로 들어오는 연결이었다.
- frontend 새 파드 하나가 시작 직후 한 번 죽었다. 07:24:57에 `[emerg] host not found in upstream "management-service"`로 종료되어 `CrashLoopBackOff`를 거쳐 07:25:30에 `1/1`이 되었다. nginx가 시작할 때 upstream 이름을 한 번만 해석하는데, 그때 w1 CPU가 87%였다. 다른 frontend 파드가 w1에 있었다.

**한 대에 모든 파드를 올렸을 때 (같은 날 별도 관찰)**

| 시각 (UTC) | 본 것 |
|---|---|
| 06:47 | 파드가 모두 w1에 있는 상태. `kubectl top` w1 3685Mi(69%), w2 1223Mi. 비정상 파드 없음 |
| 06:57:53 | CI 봇이 이미지 태그를 갱신했다. 06:58:36~52에 w2를 비웠다 |
| 약 07:00:30 | 새 버전 카나리 파드가 생겨 w1에 JVM 파드(management, redirect)가 6개가 되었다 |
| 07:05:41 | w1 load 51.66 / 30.26 / 13.66 (vCPU 2개) |
| 07:09:53 | w1 **available 559Mi**, load 45.31. redis `0/1`, `kubectl top`이 `Metrics API not available`, metrics-server 재시작 |
| 07:14 전후 | argo-rollouts 컨트롤러 `0/1`, 재시작 5회. 롤아웃이 12분 넘게 첫 단계(`Current Step Index: 0`, `more replicas need to be updated`)에서 멈춤 |
| 약 07:16 | argo-rollouts 컨트롤러와 옛 버전 redirect·management 각 1개를 지워 w2로 옮김 (5.1.1과 같은 방식) |
| 07:16:47 → 07:18:38 → 07:22:19 | w1 load 23.87 → 5.66 → 0.40 (1분 평균). 컨트롤러가 돌아와 롤아웃이 이어졌다 |

- 한 대에 모든 파드를 올린 정상 상태는 메모리 69%로 버텼다. 새 배포가 겹치자 available이 559Mi까지 내려가고 load가 50 가까이 올랐으며, 롤아웃 컨트롤러까지 죽어 배포가 멈췄다.
- `sudo dmesg -T`에서 `out of memory` 흔적은 나오지 않았다. 커널이 프로세스를 죽인 기록은 없다.
- 측정하지 못한 것: `sy`, 포화가 CPU 때문인지 메모리 때문인지의 구분, 같은 상황이 다시 재현되는지.

### 5.2 w1 drain (DB 노드)
계획한 시험은 하지 않았다. 아래는 2026-11-09 워커 메모리 증설(#295) 중 w1을 비우고 VM을 껐다 켜면서 본 값이다. 측정 스크립트는 쓰지 않았고 명령 출력과 `kubectl get pod`의 상태 전이 시각에서 읽었다.
시험 전에 백업을 만든다(2절 3번). (증설 때도 etcd, MySQL 백업을 먼저 만들었다.)

| 항목 | 기록 |
|---|---|
| 시험 일시 | 2026-11-09 약 05:39 UTC에 w1 drain, 이후 VM 종료·메모리 변경·부팅. w1이 다시 스케줄 가능해진 뒤 `mysql-0`이 배정됨(`PodScheduled` 05:47:46) |
| `mysql-0`, `seaweedfs-0`의 상태 변화 | drain으로 쫓겨난 뒤 둘 다 `Pending`(볼륨이 w1에 묶여 있어서). w1이 돌아온 뒤 배정되어 `Ready`가 되었다 |
| DB가 끊긴 구간과 영향받은 기능 | 약 05:39 ~ 05:50:11, 약 11분. 대부분이 VM을 끄고 메모리를 바꾸는 사람 작업 시간이다. DB가 없는 동안 redirect-service 파드 1개가 `CrashLoopBackOff`(`1/2`)였다. 사용자 기능별 영향은 측정하지 못했다 |
| w1 복귀 후 `mysql-0` 정상화까지 | 배정 05:47:46 → `Initialized` 05:49:39 → `Ready` 05:50:11, **2분 25초**. `seaweedfs-0`은 `Ready` 05:50:19, 2분 33초. 늦어진 1분 53초(`Initialized`까지)는 파드 샌드박스 생성 실패 때문이다: calico가 API 서비스(`10.96.0.1:443`)에 `connection refused`(VM 부팅 직후로 추정). 재시도로 저절로 해결되었다 |
| 데이터 이상 여부 (행 수 비교 등) | 확인하지 못했다. 복원·행 수 비교는 MySQL 복원 시험에서 한다 |

## 6. 이후 개선 후보
- ingress-nginx-controller를 2개로 늘리고 서로 다른 노드에 두기 (**적용·검증함**. 5.1.2에서 프론트 중단이 22초에서 1초로 줄었다. 이미 돌고 있는 클러스터에 적용하면 새 파드 2개가 같은 노드에 올라갈 수 있어서, 적용 뒤 `kubectl -n ingress-nginx get pods -o wide`로 확인하고 같으면 한 개를 지워 다시 올린다)
- 점검 전에 남는 워커의 메모리 여유를 확인하고, 옮겨 갈 파드의 requests 합이 남는 노드의 allocatable을 넘지 않게 하기 (2차 시험에서 w1 메모리 requests가 87%였다). 노드 메모리를 늘리거나 requests를 조정하는 것도 후보다
- Argo CD 파드에 resource requests를 주기 (5.1.2에서 시험했으나 효과를 확인하지 못했다. 워커 한 대에 모든 파드를 올릴 용량이 없어서 controller가 10분 넘게 Pending이었고, w1이 가득 차 redirect 두 번째 파드도 Pending이 되었다. 워커 메모리를 늘리거나 앱 requests를 줄이는 것과 같이 판단해야 한다)
- redirect, management에 PDB를 추가해 동시에 내려가지 않게 하기 (`minAvailable: 1`이면 drain이 한 개씩 순서대로 진행된다)
- management, redirect, Argo CD 파드를 두 워커에 나눠 놓기 (`topologySpreadConstraints` 또는 파드 반(反)친화성). 한 노드에 몰려 있으면 그 노드 점검이 곧 서비스 중단이다
- 볼륨이 노드에 묶인 구조(MySQL, SeaweedFS) 때문에 **DB 노드 점검 = DB 중단**임을 감수할지, 복제나 백업 복원 절차로 줄일지 결정하기
- 워커 VM 메모리를 약 3.2GB에서 약 5.3GB로 늘리기 (**적용·검증함**. 5.1.3, #295). 한 노드에 모든 파드를 올려도 메모리는 69%였고 drain 뒤 available이 1.4Gi였다. 다만 새 배포가 겹치면 부족했다
- 워커 vCPU를 2개에서 늘리는 것 검토. 5.1.3에서 w1 CPU가 87~92%로 1분 넘게 이어졌고, 배포가 겹치면 load가 51까지 올랐다. 메모리와 달리 아직 손대지 않았다
- 노드를 비우기 전에 롤아웃이 진행 중이 아닌지 확인하고, 한 노드로 몰려 있는 동안에는 `develop` 머지를 미루기 (2절 5번)
- frontend의 nginx가 시작할 때 upstream(`management-service`) 이름을 한 번만 해석해서 그 순간 DNS가 안 풀리면 `host not found in upstream`으로 죽고 다시 시작한다(5.1.3, 약 25초). 요청마다 해석하게(`resolver`와 변수 사용) 바꾸는 것을 검토 (프론트 담당)
