# prod(AWS) 배포·정리 절차서

AWS 운영 환경(prod)을 올리고, 동작을 확인하고, 정리(destroy)하는 절차다.
2026-10-22(로컬 apply)와 10-27(terraform CI apply·destroy) 실제 실행에서 막혔던 지점을 반영했다.

| 대상 | 어떻게 바뀌나 |
|---|---|
| 인프라 (VPC·EKS·RDS·Redis·CloudFront·WAF·ACM·DNS·ArgoCD) | GitHub Actions `Terraform (prod)` 워크플로가 terraform을 실행한다. apply·destroy는 승인 후에만 실행된다 |
| 앱 | ArgoCD가 Git(`infra/argocd/prod` → `infra/k8s/overlays/prod`)을 보고 배포한다. kubectl은 확인용으로만 쓴다 |
| 이미지 | 온프렘과 같은 GHCR 이미지. develop 머지 때 CI 봇이 dev·prod 태그를 커밋하고, **릴리스 PR(develop → main) 머지가 곧 prod 배포**다 |

PowerShell 명령은 **레포 루트 기준**이다. 다른 경로에서 실행하는 단계는 `cd`를 같이 적었다.

## 목차
1. [전체 흐름](#1-전체-흐름)
2. [로컬 준비](#2-로컬-준비)
3. [최초 1회 (계정당)](#3-최초-1회-계정당)
4. [prod apply](#4-prod-apply)
5. [배포 확인](#5-배포-확인)
6. [소셜 로그인 확인 (프론트 임시 배포)](#6-소셜-로그인-확인-프론트-임시-배포)
7. [정리 (destroy)](#7-정리-destroy)
8. [알려진 문제와 해결](#8-알려진-문제와-해결)

---

## 1. 전체 흐름

```
[최초 1회]  Route53 호스팅 존 → bootstrap(로컬) → OAuth 값 입력 → GitHub 설정(Environment·Variables)
[올리기]    Actions → Terraform (prod) → apply      : plan 확인 → 승인 → 적용 (약 20분)
[확인]      kubectl·ArgoCD 화면·curl, 필요하면 프론트 임시 배포 후 로그인
[정리]      Actions → Terraform (prod) → destroy    : plan 확인 → 승인 → k8s 정리 → 삭제
[운영 반영] 릴리스 PR(develop → main) 머지 → 인프라 변경이 있으면 apply(승인) / 앱은 ArgoCD가 배포
```

| 워크플로 실행 | 하는 일 | 승인 |
|---|---|---|
| PR (`infra/terraform/**` 변경) | `plan -refresh=false`(코드 변경분만) → PR 댓글 | 없음 |
| main push | plan → apply (운영 사양, ArgoCD `main`) | 필요 |
| 수동 실행 | `plan` / `apply` / `destroy`, 사양 `test-lite` / `prod`, ArgoCD `main` / `develop` | apply·destroy만 필요 |

---

## 2. 로컬 준비

로컬에서는 **bootstrap(계정당 한 번), OAuth 값 입력, kubectl 확인, CI가 막혔을 때의 예비 실행**만 한다.

### 도구
| 도구 | 확인 |
|---|---|
| AWS CLI v2 | `aws --version` |
| Terraform 1.10 이상 | `terraform version` |
| kubectl | `kubectl version --client` |
| Node 20 (프론트 임시 배포 때만) | `node --version` |

### AWS 자격증명 (MFA 세션)
계정에 `Likelion-Force-MFA` 정책이 있어 **MFA 없이 들어온 요청은 모두 거부**된다(`explicit deny ... Likelion-Force-MFA`).
MFA 코드로 임시 자격증명을 받아 **같은 PowerShell 창**에서 작업한다.

```powershell
# 액세스 키를 프로필로 등록 (최초 1회). region: ap-southeast-1
aws configure --profile snipy

# MFA 디바이스 ARN 확인 (콘솔: IAM → 사용자 → 보안 자격 증명 → MFA의 식별자)
aws iam list-mfa-devices --user-name <IAM 사용자> --profile snipy --query "MFADevices[].SerialNumber"

# 이전 세션이 남아 있으면 지운다 (만료된 세션으로 요청하면 ExpiredToken)
Remove-Item Env:AWS_ACCESS_KEY_ID, Env:AWS_SECRET_ACCESS_KEY, Env:AWS_SESSION_TOKEN -ErrorAction SilentlyContinue

$c = (aws sts get-session-token --profile snipy `
  --serial-number "<MFA 디바이스 ARN>" `
  --token-code <OTP 6자리> --duration-seconds 43200 | ConvertFrom-Json).Credentials
$env:AWS_ACCESS_KEY_ID     = $c.AccessKeyId
$env:AWS_SECRET_ACCESS_KEY = $c.SecretAccessKey
$env:AWS_SESSION_TOKEN     = $c.SessionToken

aws sts get-caller-identity      # Arn이 본인 IAM 사용자인지 확인
```
- 세션은 12시간 유효하고, 창을 닫으면 사라진다.
- `MultiFactorAuthentication failed`: ARN이 틀렸거나, 이미 쓴(또는 바뀌기 직전의) OTP 코드다. 새 코드로 다시 한다.

### Windows: terraform 경로 길이
레포 경로가 길어 `terraform init`이 모듈을 받다가 실패할 수 있다(`Filename too long`).
```powershell
$env:TF_DATA_DIR = "$env:TEMP\tfb"    # bootstrap. env/prod 예비 실행은 "$env:TEMP\tfp"
```

---

## 3. 최초 1회 (계정당)

### 3-1. Route53 호스팅 존과 NS 위임
terraform은 호스팅 존을 **조회만** 한다. destroy해도 NS가 바뀌지 않게 하려는 것이다.
1. Route53 → 호스팅 영역 생성 → `snipy.life`, 퍼블릭. NS 레코드 4개를 메모한다.
2. 도메인 등록처의 네임서버를 그 4개로 **교체**한다.
3. 확인: `nslookup -type=NS snipy.life 8.8.8.8` → 새 NS 4개. `Server failed`면 등록처가 아직 옛 NS를 가리킨다.

**NS가 반영되기 전에 apply하면 ACM 인증서 검증에서 최대 75분 기다리다 실패한다.**

### 3-2. bootstrap (로컬)
state 버킷, ECR, GitHub OIDC 역할(앱 CI·terraform plan·terraform apply), 소셜 로그인 OAuth 시크릿을 만든다.
**로컬 state**(`bootstrap/terraform.tfstate`, gitignore 대상)를 쓰므로 그 파일을 지우지 않는다.
```powershell
cd infra/terraform/bootstrap
$env:TF_DATA_DIR = "$env:TEMP\tfb"
terraform init
terraform plan      # create·update만 있는지 확인. replace·destroy가 있으면 멈춘다
terraform apply
terraform output    # terraform_plan_role_arn, terraform_apply_role_arn, github_actions_role_arn, oauth_secret_name
cd ../../..
```

**이미 있는 자원 때문에 실패할 때** (예전 프로젝트에서 남은 것, `EntityAlreadyExists`). 태그(`Project = snipy`)로 우리 자원인지 확인한 뒤 import한다.
```powershell
terraform import aws_iam_openid_connect_provider.github_actions "arn:aws:iam::<계정 ID>:oidc-provider/token.actions.githubusercontent.com"
terraform import aws_iam_role.github_actions_ci snipy-github-actions-ci
terraform import aws_iam_role_policy.github_actions_ecr_push "snipy-github-actions-ci:snipy-github-actions-ecr-push"
terraform import aws_iam_role_policy.github_actions_frontend_deploy "snipy-github-actions-ci:snipy-github-actions-frontend-deploy"
terraform plan    # update in-place만 있어야 한다
```

### 3-3. 소셜 로그인 OAuth 값 입력
`snipy-cluster/app/oauth`(bootstrap이 만든 시크릿)에 **JSON 객체 하나**로 넣는다. terraform은 이 값을 관리하지 않으므로 prod를 지워도 남고, terraform state에도 들어가지 않는다.
```powershell
notepad "$env:TEMP\oauth.json"
# {"KAKAO_CLIENT_ID":"...","KAKAO_CLIENT_SECRET":"...","GOOGLE_CLIENT_ID":"...","GOOGLE_CLIENT_SECRET":"...","NAVER_CLIENT_ID":"...","NAVER_CLIENT_SECRET":"..."}
aws secretsmanager put-secret-value --secret-id snipy-cluster/app/oauth --secret-string "file://$env:TEMP\oauth.json"
Remove-Item "$env:TEMP\oauth.json"

# 키 이름만 확인 (6개)
$s = (aws secretsmanager get-secret-value --secret-id snipy-cluster/app/oauth --query SecretString --output text) -join "`n"
($s | ConvertFrom-Json) | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name
```
- 쓰지 않는 provider는 **키를 빼면** 그 로그인이 꺼진다(Secrets Manager는 빈 문자열을 저장할 수 없다).
- **값을 넣기 전에 prod를 올리면** ExternalSecret 동기화가 실패해 management-service가 뜨지 않는다.
- 값을 바꾼 뒤 바로 반영하려면 (ESO는 1시간마다 동기화):
  ```powershell
  kubectl annotate externalsecret management-service-secrets force-sync=$(Get-Date -Format o) --overwrite
  kubectl rollout restart deploy/management-service
  ```

### 3-4. GitHub 설정
**Environment** (Settings → Environments). **워크플로가 처음 실행되기 전에** 만든다. 없으면 GitHub이 보호 설정 없이 자동으로 만들어, 승인 없이 apply 역할을 받게 된다.

| 이름 | Required reviewers | Deployment branches |
|---|---|---|
| `production-plan` | 없음 | Selected branches: `main`, `develop` |
| `production` | 승인자 지정 | Selected branches: `main`, `develop` |

**승인자를 추가한 뒤 반드시 `Save protection rules`를 누르고, 저장됐는지 확인한다.** 2026-10-27에 저장이 빠져 승인 없이 apply가 실행됐다.
```powershell
curl.exe -s https://api.github.com/repos/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03/environments | Select-String required_reviewers
```

**Variables / Secrets** (Settings → Secrets and variables → Actions)
| 종류 | 이름 | 값 |
|---|---|---|
| Variables | `TF_PLAN_ROLE_ARN` | bootstrap output `terraform_plan_role_arn` |
| Variables | `TF_APPLY_ROLE_ARN` | bootstrap output `terraform_apply_role_arn` |
| Variables | `AWS_CI_ROLE_ARN` | bootstrap output `github_actions_role_arn` |
| Variables | `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `NAVER_CLIENT_ID` | 프론트 빌드용 클라이언트 ID (온프렘·prod 공용) |
| Variables | `PROD_APP_URL` / `PROD_API_BASE_URL` | `https://app.snipy.life` / `https://snipy.life/api` |
| Variables | `PROD_SOCIAL_PROVIDERS` | `kakao,google,naver` |
| Variables | `PROD_FRONTEND_S3_BUCKET` | `snipy-cluster-frontend-team03` |
| Variables | `CI_BOT_APP_ID` | CI 봇 GitHub App ID (#119) |
| Secrets | `CI_BOT_PRIVATE_KEY` | CI 봇 App 개인 키 (#119) |

- CI 봇 App(`team03-ci-bot`)은 이미지 태그 커밋을 develop에 push한다. develop 보호 규칙의 "PR 우회 허용"에만 들어 있고, main에는 넣지 않는다.

---

## 4. prod apply

### 4-1. CI로 실행 (기본)
**Actions → Terraform (prod) → Run workflow**
| 항목 | 값 |
|---|---|
| Use workflow from | `develop`(릴리스 전 검증) 또는 `main` |
| action | `apply` |
| spec | `test-lite`(축소 사양: NAT 1, t3.medium 3대, RDS 단일 AZ, Redis 1노드, 삭제 보호 끔) 또는 `prod` |
| argocd_revision | `develop`(릴리스 전 검증) 또는 `main` |

1. **Plan job**(승인 없음, 1~2분)이 끝나면 실행 화면의 **Summary**에서 plan을 확인한다. 빈 상태에서 test-lite는 `154 to add`, 운영 사양은 `158 to add`다.
2. 실행 화면 위쪽 **Review deployments → `production` → Approve and deploy**.
3. **Apply job**이 plan을 다시 만들어 승인한 plan과 비교한 뒤 적용한다(약 16~20분).
   - `승인한 plan과 지금 plan이 다릅니다`: 승인을 기다리는 사이 코드나 자원이 바뀐 것이다. 워크플로를 다시 실행한다.

**main push(릴리스 PR 머지)** 때는 운영 사양·ArgoCD `main`으로 같은 흐름이 자동으로 시작되고 승인을 기다린다. prod가 내려가 있을 때 승인하면 운영 사양 인프라가 만들어지므로 plan을 보고 승인한다.

### 4-2. 로컬로 실행 (예비)
CI가 막혔을 때만 쓴다. `cluster_admin_arns`에 CI 역할과 `user/team03`이 같이 있어서, 누가 apply해도 관리자가 바뀌지 않는다.
```powershell
cd infra/terraform/env/prod
$env:TF_DATA_DIR = "$env:TEMP\tfp"
terraform init -backend-config="bucket=snipy-terraform-state-<계정 ID>"
terraform plan -var-file="test-lite.tfvars" -var="argocd_target_revision=develop" -out=tfplan
terraform apply tfplan
Remove-Item tfplan          # sensitive 값이 들어 있다
cd ../../..
```
- `context deadline exceeded`로 helm release가 실패하면 노드 준비가 늦은 경우가 많다. 다시 apply하면 이어서 진행한다.

### 4-3. 예전에 kubectl로 root app을 등록한 클러스터에 적용할 때
`argocd-apps` helm release가 `invalid ownership metadata`로 실패한다.
```powershell
kubectl -n argocd get application snipy-root -o jsonpath='{.metadata.finalizers}'   # 비어 있으면 지워도 하위 앱·워크로드는 남는다
kubectl -n argocd delete application snipy-root                                     # 새 root가 같은 이름이라 하위 앱을 이어받는다
```

---

## 5. 배포 확인

```powershell
aws eks update-kubeconfig --region ap-southeast-1 --name snipy-cluster
kubectl get nodes                       # 3대 Ready (cluster_admin_arns의 사용자만 접근 가능)
kubectl -n argocd get applications      # 4개 Synced / Healthy. 처음 몇 분은 Degraded·Progressing일 수 있다
```

ArgoCD 동기화 순서:
| wave | 앱 / 리소스 |
|---|---|
| -1 | `snipy-prod-prometheus-crds` (Prometheus CRD) |
| 0 | `snipy-prod` — 그 안에서 시크릿 저장소(-3) → ExternalSecret(-2) → 마이그레이션 Job(-1) → 앱(0) |
| 1 | `snipy-prod-observability` (kube-prometheus-stack, Grafana) |

**ArgoCD 3.x는 리소스별 health를 Application 객체에 저장하지 않는다.** 어느 리소스가 Degraded인지는 ArgoCD 화면에서 본다.
```powershell
$pw = kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}"
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($pw))      # admin 비밀번호
kubectl -n argocd port-forward svc/argocd-server 8080:443              # https://localhost:8080
```

| 확인 | 명령 | 정상 |
|---|---|---|
| 마이그레이션 → 앱 순서 | `kubectl get pods,jobs` | `db-migration` Complete, 앱 파드가 Job보다 늦게 생성되어 Running |
| 시크릿 | `kubectl get clustersecretstore,externalsecret` | `Valid`, 3개 모두 `SecretSynced` |
| 시크릿 키 | `(kubectl get secret management-service-secrets -o json \| ConvertFrom-Json).data.PSObject.Properties.Name` | `KAKAO_*`·`GOOGLE_*`·`NAVER_*`·`REDIS_PASSWORD`·`JWT_SECRET`·`DB_APP_PASSWORD` |
| DNS | `nslookup alb.snipy.life 8.8.8.8` | ALB IP (external-dns가 자동 생성) |
| 전체 경로 | `curl.exe -i https://snipy.life/notexist123` | `404` + `Via: ... CloudFront` |
| API | `curl.exe -i https://snipy.life/api/links` | `401` JSON |
| ALB 직접 접근 차단 | `curl.exe -i --max-time 10 https://alb.snipy.life/` | 타임아웃 |
| 모니터링 | `kubectl -n monitoring get pods`, `kubectl get servicemonitors` | Running, ServiceMonitor 2개 |

PowerShell의 `curl`은 `Invoke-WebRequest` 별칭이라 `curl.exe`를 쓴다.

---

## 6. 소셜 로그인 확인 (프론트 임시 배포)

prod 프론트(S3)는 **main 머지 때만** frontend-ci가 배포한다. main 릴리스 전에 확인하려면 로컬에서 빌드해 올린다.
client ID는 **Secrets Manager에서 읽어** 백엔드와 같은 값을 쓴다 (예시 자리표시자를 그대로 빌드하면 카카오 KOE101).
```powershell
cd src/frontend
$o = ((aws secretsmanager get-secret-value --secret-id snipy-cluster/app/oauth --query SecretString --output text) -join "`n") | ConvertFrom-Json
$env:VITE_API_BASE_URL        = "https://snipy.life/api"
$env:VITE_SOCIAL_PROVIDERS    = "kakao,google,naver"
$env:VITE_KAKAO_CLIENT_ID     = $o.KAKAO_CLIENT_ID
$env:VITE_KAKAO_REDIRECT_URI  = "https://app.snipy.life/auth/kakao/callback"
$env:VITE_GOOGLE_CLIENT_ID    = $o.GOOGLE_CLIENT_ID
$env:VITE_GOOGLE_REDIRECT_URI = "https://app.snipy.life/auth/google/callback"
$env:VITE_NAVER_CLIENT_ID     = $o.NAVER_CLIENT_ID
$env:VITE_NAVER_REDIRECT_URI  = "https://app.snipy.life/auth/naver/callback"
npm ci; npm run build

# 해시된 파일은 길게 캐시, index.html은 매번 확인 (frontend-ci와 같은 방식)
aws s3 sync dist s3://snipy-cluster-frontend-team03 --delete --exclude "index.html" --cache-control "public, max-age=31536000, immutable"
aws s3 cp dist/index.html s3://snipy-cluster-frontend-team03/index.html --cache-control "no-cache, no-store, must-revalidate"

# CloudFront에 남은 예전 index.html을 비운다 (안 하면 예전 빌드가 계속 보인다)
$dist = aws cloudfront list-distributions --query "DistributionList.Items[?Aliases.Items && contains(Aliases.Items, 'app.snipy.life')].Id | [0]" --output text
aws cloudfront create-invalidation --distribution-id $dist --paths '/*'
cd ../..
```
1~2분 뒤 https://app.snipy.life 에서 `Ctrl+F5` 후 세 provider로 로그인한다.

**각 개발자 콘솔 등록 값** (온프렘 `http://localhost:3000/...` 값은 지우지 않고 추가한다)
| provider | 등록 위치 | 값 |
|---|---|---|
| 카카오 | 플랫폼 → Web 사이트 도메인 / 카카오 로그인 → Redirect URI | `https://app.snipy.life` / `https://app.snipy.life/auth/kakao/callback` |
| 구글 | OAuth 클라이언트 → JavaScript 원본 / 리디렉션 URI, 동의 화면 → 승인된 도메인 | `https://app.snipy.life` / `.../auth/google/callback`, `snipy.life` |
| 네이버 | API 설정 → PC 웹 → Callback URL | `https://app.snipy.life/auth/naver/callback` |

| 오류 | 원인 |
|---|---|
| 카카오 KOE101 | `client_id`가 잘못됨 — 주소창 URL의 `client_id`를 확인 (자리표시자·다른 키) |
| `redirect_uri_mismatch`(구글), KOE006(카카오), Callback URL 불일치(네이버) | 콘솔 등록 값과 다름 |
| 구글 접근 차단 | 동의 화면이 "테스트" 상태인데 테스트 사용자가 아님 |
| 네이버 권한 오류 | 검수 전이라 멤버관리에 없는 계정 |
| 새로 올렸는데 예전 화면 | CloudFront 캐시 — 위의 무효화 실행 |

---

## 7. 정리 (destroy)

### 7-1. CI로 실행 (기본)
**Actions → Terraform (prod) → Run workflow**: action `destroy`, apply 때와 같은 spec·argocd_revision.
1. Plan job Summary에서 `N to destroy` 확인 → **Review deployments → `production` → Approve**
2. Destroy job이 순서대로 실행한다.
   - destroy plan을 다시 만들어 승인한 plan과 비교 (다르면 아무것도 지우지 않고 멈춤)
   - k8s 정리: ArgoCD 앱 → 앱 리소스 → `monitoring` 네임스페이스(EBS 볼륨) → ALB 삭제 대기
   - 비교를 통과한 destroy plan 적용

k8s 정리를 먼저 하는 이유: ALB가 남아 있으면 ALB가 쓰는 보안 그룹을 지우지 못해 destroy가 멈추고, PVC를 지우지 않으면 EBS 볼륨이 계정에 남아 비용이 나간다.

**끝난 뒤 확인** (모두 비어 있어야 하고, Route53에는 NS·SOA만 남는다)
```powershell
aws eks list-clusters
aws elbv2 describe-load-balancers --query "LoadBalancers[?contains(LoadBalancerName,'snipying')].LoadBalancerName"
aws ec2 describe-volumes --filters "Name=status,Values=available" --query "Volumes[].VolumeId"
aws route53 list-resource-record-sets --hosted-zone-id <존 ID> --query "ResourceRecordSets[].[Name,Type]" --output table
```

**중간에 실패했을 때**: 원인을 고친 뒤 같은 값으로 다시 실행하면 남은 자원만 지운다(이미 지운 것은 plan에서 빠진다).

**운영 사양(삭제 보호 켬)을 지울 때**: RDS 삭제 보호 때문에 destroy가 실패한다. 먼저 spec `test-lite`로 apply해 삭제 보호를 끈 뒤 destroy한다.

### 7-2. 로컬로 실행 (예비)
```powershell
aws eks update-kubeconfig --region ap-southeast-1 --name snipy-cluster
kubectl -n argocd delete application snipy-root snipy-prod snipy-prod-observability snipy-prod-prometheus-crds
kubectl delete -k infra/k8s/overlays/prod       # ServiceMonitor "not found"는 무시
kubectl delete namespace monitoring
# ALB가 없어지고 kubectl get pv 가 비면
cd infra/terraform/env/prod
terraform destroy -var-file="test-lite.tfvars"
cd ../../..
```

**남는 것**: bootstrap(state 버킷, ECR, OIDC 역할, OAuth 시크릿)과 Route53 호스팅 존(월 $0.5). 지우지 않는다. OAuth 시크릿은 `prevent_destroy`와 7일 복구 기간으로 보호된다.

---

## 8. 알려진 문제와 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| 모든 aws·terraform 요청이 `explicit deny ... Likelion-Force-MFA` | MFA 없는 자격증명 | [2. 로컬 준비](#aws-자격증명-mfa-세션)의 세션 토큰 |
| `get-session-token`이 `ExpiredToken` | 만료된 세션이 환경변수에 남아 있음 | `AWS_*` 환경변수를 지우고 다시 받기 |
| `get-session-token`이 `MultiFactorAuthentication failed` | MFA ARN 오류 또는 이미 쓴 OTP | ARN 확인, 새 코드 |
| `terraform init`에서 `Filename too long` | Windows 경로 길이 | `$env:TF_DATA_DIR`를 짧은 경로로 |
| `nslookup`이 `Server failed` | 등록처가 옛 NS를 가리킴 | 등록처 네임서버를 새 존 NS로 교체 |
| bootstrap `EntityAlreadyExists` | 예전 OIDC provider·역할이 남아 있음 | [3-2](#3-2-bootstrap-로컬)의 import |
| 승인 없이 apply가 실행됨 | `production` 승인자가 저장되지 않음 | [3-4](#3-4-github-설정)의 저장·확인 |
| Plan job이 자격증명 단계에서 실패 | Variables 이름 오류, 또는 Environment 브랜치 제한 밖에서 실행 | `TF_*_ROLE_ARN` 확인, `main`·`develop`에서 실행 |
| `승인한 plan과 지금 plan이 다릅니다` | 승인 대기 중 코드·자원 변경 | 워크플로 다시 실행 |
| `kubectl -n argocd get applications`가 잠시 Degraded | 앱 기동 중 (HPA 지표 수집 등) | 몇 분 뒤 다시 확인, 계속이면 ArgoCD 화면에서 리소스 확인 |
| ArgoCD 리소스 health가 kubectl로 안 보임 | ArgoCD 3.x는 리소스 health를 Application에 저장하지 않음 | ArgoCD 화면(port-forward) |
| external-dns가 `All records are already up to date`만, `alb` 레코드 없음 | v0.22부터 어노테이션 접두사가 `external-dns.kubernetes.io/` (`external-dns.alpha...`는 무시) | Ingress 어노테이션 접두사 확인 (#89) |
| 카카오 KOE101 | 프론트 빌드의 `client_id`가 자리표시자 등 잘못된 값 | [6](#6-소셜-로그인-확인-프론트-임시-배포)처럼 Secrets Manager 값으로 빌드 |
| 새로 올린 프론트가 안 보임 | CloudFront가 예전 index.html 캐시 | index.html `no-cache` 업로드 + 무효화 |
| `invalid ownership metadata` (argocd-apps) | kubectl로 만든 `snipy-root`가 있음 | [4-3](#4-3-예전에-kubectl로-root-app을-등록한-클러스터에-적용할-때) |
| destroy가 `BucketNotEmpty` | 버킷에 파일이 남아 있음 (프론트 버킷은 이후 `force_destroy`) | `aws s3 rm s3://<버킷> --recursive` 후 다시 실행 |
| destroy가 보안 그룹·서브넷에서 멈춤 | ALB(또는 ENI)가 남아 있음 | k8s 정리 후 다시 실행 |
| destroy 후에도 EBS 비용 | PVC를 지우지 않고 클러스터를 지움 | EC2 → 볼륨에서 `available` 상태 볼륨 삭제 |
