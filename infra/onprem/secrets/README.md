# 온프렘 시크릿 관리 (Sealed Secrets)

시크릿 값을 암호화한 파일(`*.sealed.yaml`)로 Git에 올리고, 클러스터의 Sealed Secrets 컨트롤러가 복호화해서 일반 Secret으로 만든다. AWS를 쓰지 않는 온프렘용이다. 클라우드는 기존대로 External Secrets와 AWS Secrets Manager를 쓴다.

- 암호화된 파일은 그 클러스터의 개인 키로만 풀린다. Git에 올라가도 값을 알 수 없다.
- 반대로 클러스터 밖에서는 파일에서 값을 되찾을 수 없다. 원본 값은 팀 비밀번호 관리자에 따로 보관한다.
- 컨트롤러의 개인 키를 잃으면 모든 `*.sealed.yaml`을 풀 수 없다. 키 백업이 가장 중요하다 (아래 "2. 개인 키 백업").

## 파일

| 파일 | 역할 |
|---|---|
| `seal-secrets.sh` | 값을 읽어 `<이름>.sealed.yaml`을 만든다 |
| `secrets.example.env` | 값 파일 템플릿. 복사해서 `secrets.env`로 쓴다 (커밋 안 됨) |
| `sealed-secrets-pub.pem` | 봉인에 쓰는 공개 인증서. 저장소에 들어 있고 커밋해도 안전하다. 루트 `.gitignore`의 `*.pem` 규칙은 이 폴더 `.gitignore`에서 이 파일만 예외로 푼다. 클러스터를 새로 만들면 3번으로 다시 받아 바꾼다 |
| `*.sealed.yaml` | 봉인된 결과. 커밋한다 |
| `.gitignore` | `secrets.env`와 키 백업 파일을 커밋하지 않게 한다 |

만드는 Secret은 클라우드와 같은 이름과 키를 쓴다.

| Secret (`snipy` 네임스페이스) | 키 |
|---|---|
| `mysql-secrets` | `MYSQL_ROOT_PASSWORD`, `DB_APP_PASSWORD`, `DB_MIGRATOR_PASSWORD`, `READONLY_PASSWORD` |
| `management-service-secrets` | `DB_APP_PASSWORD`, `JWT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` |
| `redirect-service-secrets` | `DB_APP_PASSWORD`, `JWT_SECRET` |
| `seaweedfs-s3-secrets` | `S3_ACCESS_KEY`, `S3_SECRET_KEY` |

Secret 이름과 네임스페이스는 봉인할 때 고정된다 (`--scope strict`). 이름을 바꾸면 다시 봉인해야 한다.

## 1. 컨트롤러 설치 (한 번, cp1)

cp1에서 root로 실행한다.

```bash
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.40.0/controller.yaml
kubectl -n kube-system rollout status deploy/sealed-secrets-controller
```

`kube-system`에 `sealed-secrets-controller`가 뜨면 된다. 이때 컨트롤러가 개인 키를 만든다.

## 2. 개인 키 백업

설치 직후에 바로 한다. 컨트롤러는 30일마다 새 키를 만든다(옛 키는 유지). 백업은 키가 새로 생길 때마다 다시 받는다.

```bash
kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > sealed-secrets-key-backup.yaml
```

받은 파일을 팀 비밀번호 관리자 같은 안전한 곳에 옮기고, cp1에 남은 파일은 지운다. 이 파일은 Git에 올리지 않는다 (`.gitignore`에 들어 있다).

## 3. 공개 인증서 받기

공개 인증서는 비밀이 아니다. 클러스터에 접속하지 않고도 봉인할 수 있게 저장소에 둔다. 키가 여러 개면 가장 최근 것을 쓴다.

cp1에서:

```bash
kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key --sort-by=.metadata.creationTimestamp -o jsonpath='{range .items[*]}{.data.tls\.crt}{"\n"}{end}' | tail -n 1 | base64 -d > /home/k8s/sealed-secrets-pub.pem
```

호스트로 가져와 이 폴더에 둔다.

```bash
scp k8s@192.168.122.11:/home/k8s/sealed-secrets-pub.pem infra/onprem/secrets/
```

## 4. kubeseal 설치

호스트(Git Bash)에서 한다. 버전은 컨트롤러와 같은 `0.40.0`이다.

```bash
curl -LO https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.40.0/kubeseal-0.40.0-windows-amd64.tar.gz
curl -LO https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.40.0/sealed-secrets_0.40.0_checksums.txt
sha256sum -c --ignore-missing sealed-secrets_0.40.0_checksums.txt
tar -xzf kubeseal-0.40.0-windows-amd64.tar.gz kubeseal.exe
```

`sha256sum`이 `kubeseal-0.40.0-windows-amd64.tar.gz: OK`를 출력해야 한다. 나온 `kubeseal.exe`를 `PATH`에 있는 폴더(예: `kubectl.exe`가 있는 곳)에 둔다. 확인은 `kubeseal --version`.

## 5. 값 채우기

```bash
cd infra/onprem/secrets
cp secrets.example.env secrets.env
```

`secrets.env`에 값을 채운다. 따옴표 없이 `KEY=값`으로 쓴다. 이 파일은 커밋되지 않는다.

## 6. 봉인

```bash
bash infra/onprem/secrets/seal-secrets.sh
```

`mysql-secrets.sealed.yaml`, `management-service-secrets.sealed.yaml`, `redirect-service-secrets.sealed.yaml`이 만들어진다. 열어서 `encryptedData`의 값이 긴 암호문인지 확인하고 커밋한다.

## 7. 클러스터에 적용

`snipy` 네임스페이스는 프론트, 백엔드, DB가 같이 쓰는 공용이라 `infra/onprem/base/namespace.yaml`로 한 곳에서만 관리한다. 이 폴더에는 네임스페이스를 두지 않는다.

호스트(저장소 루트)에서 필요한 파일을 cp1로 복사한다.

```bash
scp -r infra/onprem/base k8s@192.168.122.11:~/
scp infra/onprem/secrets/*.sealed.yaml k8s@192.168.122.11:~/
```

cp1(root)에서 적용한다. 네임스페이스가 먼저다.

```bash
kubectl apply -f /home/k8s/base/namespace.yaml
kubectl apply -f /home/k8s/mysql-secrets.sealed.yaml -f /home/k8s/management-service-secrets.sealed.yaml -f /home/k8s/redirect-service-secrets.sealed.yaml
```

확인한다.

```bash
kubectl -n snipy get sealedsecrets
kubectl -n snipy get secret
```

`SealedSecret`과 같은 이름의 `Secret`이 생기면 컨트롤러가 복호화한 것이다. 안 생기면 `kubectl -n kube-system logs deploy/sealed-secrets-controller`와 `kubectl -n snipy describe sealedsecret <이름>`을 본다.

## 값을 바꿀 때

1. `secrets.env`를 고친다.
2. `seal-secrets.sh`를 다시 실행한다.
3. 바뀐 `*.sealed.yaml`을 커밋하고 `kubectl apply -f`로 적용한다.
4. Secret을 환경 변수로 쓰는 파드는 다시 시작해야 값이 바뀐다 (`kubectl -n snipy rollout restart deploy/<이름>`).

## 클러스터를 다시 만들 때

개인 키가 새로 생기면 Git의 `*.sealed.yaml`을 풀 수 없다. 두 가지 중 하나로 복구한다.

키 백업이 있을 때 (이 순서를 지킨다)

1. 컨트롤러를 설치하기 전에 백업한 키를 적용한다.
   ```bash
   kubectl apply -f sealed-secrets-key-backup.yaml
   ```
2. 컨트롤러를 설치한다 (1번).
3. 컨트롤러가 백업한 키를 읽도록 파드를 다시 시작한다.
   ```bash
   kubectl -n kube-system delete pod -l name=sealed-secrets-controller
   ```
4. `kubectl apply -f`로 `*.sealed.yaml`을 다시 적용한다.

키 백업이 없을 때

1. 컨트롤러를 설치하고 3번으로 새 공개 인증서를 받아 `sealed-secrets-pub.pem`을 바꾼다.
2. 비밀번호 관리자의 원본 값으로 `secrets.env`를 채워 6번부터 다시 한다.

## 주의

- Kubernetes Secret은 암호화가 아니라 base64 인코딩이다. 같은 클러스터에서 권한이 있는 사용자는 `kubectl get secret -o yaml`로 읽을 수 있다. 접근 권한(RBAC)으로 제한한다.
- `secrets.env`, 키 백업 파일, 값이 들어간 로그를 채팅이나 커밋에 붙이지 않는다. 노출된 값은 다시 발급한다.
- 이 저장소가 공개되면 `*.sealed.yaml`도 공개된다. 개인 키가 유출되지 않는 한 안전하지만, 키가 유출되면 과거 파일이 모두 풀리니 그때는 값을 바꿔 다시 봉인한다.
- `SealedSecret`을 지우면 컨트롤러가 만든 `Secret`도 같이 지워진다 (`kubectl delete -f *.sealed.yaml`). 그 Secret을 쓰는 파드가 있으면 먼저 확인한다. 네임스페이스는 `base`에서만 관리하고 이 폴더에는 두지 않는다.
- CI에서 쓸 때는 `seal-secrets.sh`가 환경 변수를 먼저 읽으니 값을 환경 변수로 넘기면 된다. 공개 인증서는 저장소에 있다.
