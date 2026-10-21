# 온프렘 Argo CD

클러스터(ON-guide, cp-addons.sh까지 끝난 상태)에 Argo CD를 설치하고 UI에 접속하는 방법이다. 설치만 다룬다. dev 환경을 Argo CD가 관리하게 하는 Application은 `infra/k8s/overlays/dev`를 가리키도록 따로 정리한다.

- `cp-argocd.sh`: Argo CD 설치 (cp1에서 실행)

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

끝나면 파드가 모두 `Running`이어야 한다. dex와 notifications는 일부러 0개로 줄였다.

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

## 3. 앱을 등록한 뒤 알아둘 것

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

## 4. 되돌리기

Argo CD 전체를 지운다 (CRD도 같이 지워진다). 앱이 관리하던 리소스는 finalizer가 없으면 남는다.

```bash
kubectl delete -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.3.16/manifests/install.yaml
kubectl delete namespace argocd
```

## 주의

- `infra/argocd/`의 Application은 아직 온프렘에 맞게 정리되지 않았다. `snipy-dev`는 `infra/k8s/overlays/dev`를 가리키지만 `destination`이 `default`이고 `prune: true`, `automated`가 켜져 있다. observability와 prod는 AWS용이다. 정리 전에는 온프렘에서 apply하지 않는다.
- `snipy` 네임스페이스는 `infra/onprem/base`에서만 관리하고 Argo CD 앱 경로 밖에 둔다. 앱이 지워도 네임스페이스는 남는다.
- 각자 클러스터를 만들기 때문에 이 절차는 각 클러스터에서 한 번씩 한다. 설치 전에 호스트 PC와 VM의 메모리, 디스크 여유를 확인한다.
- Argo CD가 받는 이미지는 `quay.io`, `public.ecr.aws`, `ghcr.io`다. 워커에서 이 레지스트리에 접속되는지 먼저 확인한다.
