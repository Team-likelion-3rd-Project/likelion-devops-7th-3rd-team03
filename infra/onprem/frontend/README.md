# 온프렘 프론트 이미지

프론트를 nginx 이미지로 만들어 VM 클러스터의 워커에 넣는다. 매니페스트(Deployment, Service)와 배포 절차는 dev overlay로 옮겼다: [`infra/k8s/overlays/dev`](../../k8s/overlays/dev/README.md).

- `src/frontend/Dockerfile.prod`, `src/frontend/nginx/default.conf.template`: 운영 이미지
- `build-and-load.sh`: 이미지를 빌드해서 워커 두 대에 넣는다. 아직 GHCR로 옮기지 않아 tar로 옮긴다.

```
브라우저 -> 워커:30080 (ingress-nginx) -> frontend 파드(nginx) -> /api -> management-service
```

## 접속 주소 정하기

`VITE_*` 값은 빌드할 때 번들에 들어간다. 로그인 후 돌아오는 주소(redirect URI)도 접속 주소로 만들어지니, 접속 주소를 먼저 정하고 그 주소로 빌드한다. 주소를 바꾸면 다시 빌드한다.

| | IP로 접속 (기본) | localhost 터널 |
|---|---|---|
| 주소 | `http://192.168.122.21:30080` | `http://localhost:3000` |
| 로그인 | 카카오만 | 카카오, 구글 |
| 접속 가능한 곳 | 같은 네트워크 | 터널을 연 PC |

구글은 redirect URI로 IP나 HTTP 주소를 받지 않고 localhost만 받는다. 그래서 구글을 쓰려면 터널로 접속해야 한다.

### IP로 접속

1. 카카오 콘솔에 `http://192.168.122.21:30080/auth/kakao/callback`을 추가한다. 백엔드 값은 dev overlay `configmaps.yaml`의 `KAKAO_REDIRECT_URI`에 같은 주소로 들어 있다.
2. 이미지를 빌드해서 워커에 넣는다.
   ```bash
   bash infra/onprem/frontend/build-and-load.sh
   ```

### localhost 터널

1. 이미지를 빌드해서 워커에 넣는다.
   ```bash
   BASE_URL=http://localhost:3000 SOCIAL_PROVIDERS=kakao,google bash infra/onprem/frontend/build-and-load.sh
   ```
2. 백엔드도 터널 주소에 맞춘다. dev overlay `configmaps.yaml`의 `KAKAO_REDIRECT_URI`를 `http://localhost:3000/auth/kakao/callback`으로 바꿔 적용하고 `kubectl -n snipy rollout restart deploy/management-service`. 구글은 `GOOGLE_REDIRECT_URI`가 이미 터널 주소이고, `management-service-secrets`에 구글 클라이언트 값이 있어야 한다.
3. 호스트에서 3000번 포트를 쓰는 프로세스가 있으면 끄고, 터널을 연 뒤 `http://localhost:3000`으로 접속한다. 터널 터미널은 접속하는 동안 켜 둔다.
   ```bash
   ssh -N -L 3000:192.168.122.21:30080 k8s@192.168.122.21
   ```

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
| 로그인이 안 된다 | 빌드할 때 정한 주소와 다른 주소로 접속했다 | 빌드할 때 쓴 `BASE_URL`로 접속 |
| 옛 화면이 보인다 | 브라우저 캐시 | Ctrl+Shift+R. `index.html`은 캐시하지 않게 해 두었다 |

## 한계

- 이미지를 tar로 직접 넣는다. 워커가 늘면 `WORKERS`에 추가해야 한다. CI/CD 단계에서 GHCR로 옮길 예정이다.
- 구글 로그인은 터널로만 된다. 모두가 같은 주소로 쓰려면 HTTPS 도메인이 필요하다.
