# 온프렘 프론트 배포

프론트를 nginx 이미지로 만들어 VM 클러스터(cp1 + 워커 2대)에 올린다. 클러스터는 [ON-guide.md](../ON-guide.md)대로 구축된 상태여야 한다.

- `src/frontend/Dockerfile.prod`, `src/frontend/nginx/default.conf.template`: 운영 이미지
- `infra/onprem/frontend/*.yaml`: `snipy` 네임스페이스에 Deployment(파드 2개), Service, Ingress
- `infra/onprem/base/namespace.yaml`: `snipy` 네임스페이스. 다른 서비스와 공용이라 서비스 폴더 밖에 둔다
- `infra/onprem/frontend/build-and-load.sh`: 이미지를 빌드해서 워커 두 대에 넣는다. 레지스트리가 없어서 tar로 옮긴다.

```
브라우저 -> 워커:30080 (ingress-nginx) -> frontend 파드(nginx) -> /api -> API_UPSTREAM
```

`/api`를 넘길 곳은 `deployment.yaml`의 `API_UPSTREAM`이다. 지금은 호스트 PC에서 돌리는 백엔드(`http://192.168.122.1:18082`)를 가리킨다. 백엔드가 클러스터에 올라가면 이 값만 바꾸면 된다.

## 접속 주소 정하기

`VITE_*` 값은 빌드할 때 번들에 들어간다. 로그인 후 돌아오는 주소(redirect URI)도 접속 주소로 만들어지니, 접속 주소를 먼저 정하고 그 주소로 빌드한다. 주소를 바꾸면 다시 빌드한다.

| | IP로 접속 (기본) | localhost 터널 |
|---|---|---|
| 주소 | `http://192.168.122.21:30080` | `http://localhost:3000` |
| 로그인 | 카카오만 | 카카오, 구글 |
| 접속 가능한 곳 | 같은 네트워크 | 터널을 연 PC |

구글은 redirect URI로 IP나 HTTP 주소를 받지 않고 localhost만 받는다. 그래서 구글을 쓰려면 터널로 접속해야 한다.

### IP로 접속

1. 카카오 콘솔에 `http://192.168.122.21:30080/auth/kakao/callback`을 추가한다.
2. 호스트 백엔드 `.env`를 같은 주소로 맞춘다.
   - `KAKAO_REDIRECT_URI=http://192.168.122.21:30080/auth/kakao/callback`
   - `CORS_ALLOWED_ORIGINS=http://localhost:3000,http://192.168.122.21:30080`
   - 적용: `docker compose -p snipy3rd up -d management-service`
   - 로컬 개발 값과 달라지니 끝나면 되돌린다.
3. 이미지를 빌드해서 워커에 넣는다.
   ```bash
   bash infra/onprem/frontend/build-and-load.sh
   ```

### localhost 터널

로컬 개발과 같은 `http://localhost:3000/auth/<provider>/callback`을 쓰니 콘솔과 백엔드 설정은 그대로 둔다. 구글을 쓰면 백엔드 `.env`에 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`이 있어야 한다.

1. 호스트에서 3000번 포트를 쓰는 프로세스가 있으면 끈다. compose 프론트 컨테이너라면 `docker compose -p snipy3rd stop frontend`.
2. 이미지를 빌드해서 워커에 넣는다.
   ```bash
   BASE_URL=http://localhost:3000 SOCIAL_PROVIDERS=kakao,google bash infra/onprem/frontend/build-and-load.sh
   ```
3. 배포 후(아래 "배포" 참고) 터널을 열고 `http://localhost:3000`으로 접속한다. 터널 터미널은 접속하는 동안 켜 둔다.
   ```bash
   ssh -N -L 3000:192.168.122.21:30080 k8s@192.168.122.21
   ```

## 배포

사전 확인

- 호스트에서 백엔드가 떠 있다: `curl http://localhost:18082/actuator/health`
- 호스트 방화벽이 `192.168.122.0/24`에서 18082로 들어오는 접속을 허용한다. cp1에서 `curl http://192.168.122.1:18082/actuator/health`로 확인한다.

순서

1. 위에서 고른 방식으로 `build-and-load.sh`를 실행한다. 워커마다 VM 사용자 비밀번호를 묻는다.
2. 매니페스트를 cp1로 복사한다. 네임스페이스(`base`)도 같이 보낸다.
   ```bash
   scp -r infra/onprem/base infra/onprem/frontend k8s@192.168.122.11:~/
   ```
3. cp1에서 네임스페이스를 먼저 만들고 프론트를 적용한다. `snipy` 네임스페이스는 백엔드, DB와 같이 쓰는 공용이라 `frontend` 폴더에 두지 않았다.
   ```bash
   kubectl apply -f /home/k8s/base/namespace.yaml
   kubectl apply -k /home/k8s/frontend
   kubectl -n snipy rollout status deploy/frontend
   kubectl -n snipy get pods -o wide
   ```

## 확인

- 파드 2개가 서로 다른 워커에서 `Running`
- 접속 주소에서 로그인 화면이 뜬다
- `<접속 주소>/dashboard`에서 새로고침해도 404가 아니다
- 로그인, 가입, 대시보드 이동. 같은 계정으로 다시 로그인하면 가입 폼 없이 들어간다
- 파드를 하나 지워도(`kubectl -n snipy delete pod <이름>`) 접속이 유지되고 새 파드가 뜬다

## 지울 때

프론트만 지우려면 `kubectl delete -k /home/k8s/frontend`를 쓴다. 이 폴더의 Deployment, Service, Ingress만 지워지고 `snipy` 네임스페이스는 남는다. 네임스페이스는 `base`에서만 관리하고, 서비스 폴더에는 `Namespace`를 두지 않는다. 네임스페이스를 지우면 백엔드, DB, PVC까지 같이 지워지니 직접 `kubectl delete ns snipy`를 하지 않는다.

## 프론트 코드를 바꿨을 때

`build-and-load.sh`를 다시 실행한 뒤 파드를 다시 띄운다. 태그가 `onprem`으로 같아서 쿠버네티스는 이미지가 바뀐 걸 모른다.

```bash
kubectl -n snipy rollout restart deploy/frontend
```

## 문제 해결

| 증상 | 원인 | 확인 / 해결 |
|---|---|---|
| `ErrImagePull`, `ImagePullBackOff` | 워커에 이미지가 없다 | `build-and-load.sh` 다시 실행. 워커에서 `sudo ctr -n k8s.io images ls \| grep snipy-frontend` |
| `ctr images import`가 platform 오류 | 아카이브에 여러 플랫폼 정보가 있다 | `images import --all-platforms`로 다시 시도 |
| 화면은 뜨는데 `/api`가 502, 504 | VM에서 호스트 백엔드에 닿지 않는다 | 호스트 방화벽, 백엔드 실행 여부. cp1에서 `curl http://192.168.122.1:18082/actuator/health` |
| 로그인 후 `403 Invalid CORS request` | 백엔드 `CORS_ALLOWED_ORIGINS`에 접속 주소가 없다 | 접속 주소를 추가하고 백엔드 재시작 |
| 카카오 `KOE006` | 콘솔에 redirect URI가 없다 | 콘솔에 등록 |
| 카카오 로그인 후 백엔드가 400 | 백엔드 `KAKAO_REDIRECT_URI`가 프론트와 다르다 | 두 값을 맞추고 백엔드 재시작 |
| 로그인이 안 된다 | 빌드할 때 정한 주소와 다른 주소로 접속했다 | 빌드할 때 쓴 `BASE_URL`로 접속 |
| 옛 화면이 보인다 | 브라우저 캐시 | Ctrl+Shift+R. `index.html`은 캐시하지 않게 해 두었다 |

## 한계

- 이미지를 tar로 직접 넣는다. 워커가 늘면 `WORKERS`에 추가해야 한다. CI/CD 단계에서 GHCR로 옮길 예정이다.
- `/api`가 호스트 PC의 백엔드에 의존한다. 호스트가 꺼지면 API가 실패한다.
- `API_UPSTREAM`을 클러스터 서비스 이름으로 바꾸면 그 서비스가 먼저 있어야 한다. nginx는 시작할 때 주소를 확인해서, 없으면 파드가 뜨지 못한다.
- 구글 로그인은 터널로만 된다. 모두가 같은 주소로 쓰려면 HTTPS 도메인이 필요하다.
