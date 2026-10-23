# 온프렘 프론트 이미지

프론트를 nginx 이미지로 만들어 GHCR에 올리고 VM 클러스터가 받아 간다. 매니페스트(Deployment, Service)와 배포 절차는 dev overlay로 옮겼다: [`infra/k8s/overlays/dev`](../../k8s/overlays/dev/README.md).

- `src/frontend/Dockerfile.prod`, `src/frontend/nginx/default.conf.template`: 운영 이미지
- `.github/workflows/onprem-frontend-ci.yml`: develop 머지 때 이미지를 만들어 GHCR에 올리고, overlay의 프론트 태그를 그 커밋 SHA로 바꾼다. 이미지는 `linux/amd64`와 `linux/arm64`를 한 태그에 같이 올려서 Apple Silicon 맥의 로컬 클러스터도 같은 태그를 받는다.
- `build-and-load.sh`: CI를 쓸 수 없을 때 손으로 하는 길. 기본은 워커 두 대에 tar로 넣고, `PUSH=1`이면 GHCR로 올린다.

```
브라우저 -> 워커:30080 (ingress-nginx) -> frontend 파드(nginx) -> /api -> management-service
```

## 접속 주소

`VITE_*` 값은 빌드할 때 번들에 들어가고, 로그인 후 돌아오는 주소(redirect URI)도 접속 주소로 만들어진다. 구글이 redirect URI로 IP나 HTTP 주소를 받지 않고 localhost만 받는다. 그래서 카카오·구글·네이버 로그인을 모두 켜려고 **localhost 터널 주소 `http://localhost:3000`으로 통일**했다. `localhost:3000`은 모든 PC에서 같은 주소라서 CI가 만든 이미지 하나를 팀원 전원이 쓴다.

| 접속 주소 | 로그인 | 접속 가능한 곳 |
|---|---|---|
| `http://localhost:3000` (터널) | 카카오, 구글, 네이버 | 터널을 연 PC |
| `http://192.168.122.21:30080` | 되지 않는다 (화면 확인만) | 같은 네트워크 |

### 접속하는 법

1. 각 콘솔에 `http://localhost:3000/auth/<provider>/callback`을 등록한다 (`kakao`, `google`, `naver`). 백엔드 값은 dev overlay `configmaps.yaml`에 같은 주소로 들어 있다.
2. `management-service-secrets`에 세 provider의 클라이언트 값이 있어야 한다 ([`infra/onprem/secrets`](../secrets/README.md)). 값이 비어 있는 provider는 백엔드에서 꺼진다.
3. 호스트에서 3000번 포트를 쓰는 프로세스가 있으면 끄고, 터널을 연 뒤 `http://localhost:3000`으로 접속한다. 터널 터미널은 접속하는 동안 켜 둔다.
   ```bash
   ssh -N -L 3000:192.168.122.21:30080 k8s@192.168.122.21
   ```

### 손으로 빌드할 때

CI를 쓸 수 없을 때만 한다. 기본값이 터널 주소(`http://localhost:3000`)와 카카오·구글이다. 스크립트가 네이버 클라이언트 ID를 아직 읽지 않아 네이버는 켜지지 않는다. 구글 ID가 `.env`에 없으면 `SOCIAL_PROVIDERS=kakao`로 줄인다.

```bash
bash infra/onprem/frontend/build-and-load.sh
```

## 프론트 코드를 바꿨을 때

`src/frontend`가 develop에 머지되면 CI가 이미지를 만들어 올리고 overlay의 태그를 새 SHA로 바꾼다. 태그가 바뀌므로 파드는 Argo CD(또는 `kubectl apply -k`)가 적용할 때 새 이미지로 교체된다.

CI를 쓸 수 없을 때는 `build-and-load.sh`로 직접 만든 이미지를 워커에 넣고 파드를 다시 띄운다. 이 경로는 태그가 `onprem`으로 같아서 쿠버네티스가 이미지가 바뀐 걸 모른다.

```bash
kubectl -n snipy rollout restart deploy/frontend
```

## 문제 해결

| 증상 | 원인 | 확인 / 해결 |
|---|---|---|
| `ErrImagePull`, `ImagePullBackOff` (ghcr.io) | `kustomization.yaml`의 태그 이미지가 GHCR에 없거나 패키지가 private이다 | 태그와 패키지 public 여부 확인 ([overlay README](../../k8s/overlays/dev/README.md)의 GHCR 이미지) |
| `ErrImagePull` (tar로 넣은 이미지) | 워커에 이미지가 없다 | `build-and-load.sh` 다시 실행. 워커에서 `sudo ctr -n k8s.io images ls \| grep snipy-frontend` |
| `ctr images import`가 platform 오류 | 아카이브에 여러 플랫폼 정보가 있다 | `images import --all-platforms`로 다시 시도 |
| 로그인이 안 된다 | 터널 없이 IP 주소로 접속했거나, 콘솔에 redirect URI를 등록하지 않았다 | `http://localhost:3000`으로 접속, 터널이 열려 있는지와 콘솔 등록 확인 |
| 로그인 버튼이 안 보인다 | 이미지를 만들 때 그 provider의 클라이언트 ID가 비어 있었다 | CI 설정(저장소 Variables)과 빌드 로그 확인 |
| 옛 화면이 보인다 | 브라우저 캐시 | Ctrl+Shift+R. `index.html`은 캐시하지 않게 해 두었다 |

## 한계

- 수동 경로(tar)는 워커가 늘면 `WORKERS`에 추가해야 한다. 평소에는 CI가 올린 GHCR 이미지를 쓴다.
- 로그인은 터널로만 된다. 모두가 같은 주소로 쓰려면 HTTPS 도메인이 필요하다.
