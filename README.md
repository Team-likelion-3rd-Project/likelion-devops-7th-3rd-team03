# Snipy (URL Shortener & Link Management Platform)

<p align="center">
  <img src="https://img.shields.io/badge/Java_17-151515?style=for-the-badge&logo=openjdk&logoColor=ED8B00" alt="Java 17"> <img src="https://img.shields.io/badge/Spring_Boot_4-151515?style=for-the-badge&logo=springboot&logoColor=6DB33F" alt="Spring Boot 4"> <img src="https://img.shields.io/badge/MySQL_8.0-151515?style=for-the-badge&logo=mysql&logoColor=4479A1" alt="MySQL 8.0"> <img src="https://img.shields.io/badge/Redis_7-151515?style=for-the-badge&logo=redis&logoColor=DC382D" alt="Redis 7"><br>
  <img src="https://img.shields.io/badge/React_18-151515?style=for-the-badge&logo=react&logoColor=61DAFB" alt="React 18"> <img src="https://img.shields.io/badge/Vite-151515?style=for-the-badge&logo=vite&logoColor=646CFF" alt="Vite"><br>
  <img src="https://img.shields.io/badge/Kubernetes-151515?style=for-the-badge&logo=kubernetes&logoColor=326CE5" alt="Kubernetes"> <img src="https://img.shields.io/badge/AWS-151515?style=for-the-badge" alt="AWS"> <img src="https://img.shields.io/badge/Terraform-151515?style=for-the-badge&logo=terraform&logoColor=7B42BC" alt="Terraform"> <img src="https://img.shields.io/badge/Docker-151515?style=for-the-badge&logo=docker&logoColor=2496ED" alt="Docker"><br>
  <img src="https://img.shields.io/badge/GitHub_Actions-151515?style=for-the-badge&logo=githubactions&logoColor=2088FF" alt="GitHub Actions"> <img src="https://img.shields.io/badge/Argo_CD-151515?style=for-the-badge&logo=argo&logoColor=EF7B4D" alt="Argo CD"> <img src="https://img.shields.io/badge/Prometheus-151515?style=for-the-badge&logo=prometheus&logoColor=E6522C" alt="Prometheus"> <img src="https://img.shields.io/badge/Grafana-151515?style=for-the-badge&logo=grafana&logoColor=F46800" alt="Grafana"> <img src="https://img.shields.io/badge/k6-151515?style=for-the-badge&logo=k6&logoColor=7D64FF" alt="k6">
</p>

> **단축 URL 생성부터 유입 데이터 분석까지, 마케팅 팀을 위한 링크 관리 플랫폼**

- **링크 생성과 관리** — 긴 URL을 7자리 단축 URL로 만들고, 공유한 뒤에도 연결 페이지와 만료일을 수정하거나 즉시 차단
- **캠페인 성과 분석** — 오늘의 실시간 클릭부터 일별 추이, 유입 경로·기기·국가·시간대 분포, 링크 간 비교까지
- **팀 단위 협업** — 초대 코드로 팀원을 모으고, 링크와 통계를 팀이 소유해 담당자가 바뀌어도 관리 유지

[2차 프로젝트](https://github.com/Team-likelion-2nd-Project/likelion-devops-7th-team03)의 Snipy에 워크스페이스 기능을 추가하고, 온프렘(dev)과 AWS(prod) 두 환경을 GitOps로 운영하도록 확장했습니다.

- 배포 주소: https://app.snipy.life

---

## 팀 구성

| <img src="https://github.com/hansol-01.png" width="100"> | <img src="https://github.com/janghyn97-dotcom.png" width="100"> | <img src="https://github.com/kubistar.png" width="100"> | <img src="https://github.com/joon2730.png" width="100"> |
|:---:|:---:|:---:|:---:|
| **한소현**<br>[@hansol-01](https://github.com/hansol-01)<br>gksthgus2022@gmail.com | **박장현**<br>[@janghyn97-dotcom](https://github.com/janghyn97-dotcom)<br>janghyn97@gmail.com | **유승미**<br>[@kubistar](https://github.com/kubistar)<br>dbtmdal0205@gmail.com | **정예준**<br>[@joon2730](https://github.com/joon2730)<br>jungyejoon2730@gmail.com |
| `Cloud` | `On-Prem · Frontend` | `Backend · Data` | `Backend · Docs` |

세부 역할은 [역할 분담](#역할-분담)에 있습니다.

---

## 빠른 심사 흐름

1. https://app.snipy.life 에 접속해 카카오·구글·네이버 중 하나로 로그인합니다.
2. 개인 워크스페이스에서 원본 URL을 입력해 단축 URL을 생성합니다.
3. 생성한 단축 URL을 새 탭에서 열어 원본 페이지로 이동하는지 확인합니다.
4. 링크 통계 화면에서 오늘 클릭 수와 방문자 수가 증가했는지 확인합니다.
5. 다른 계정으로 멤버 화면의 초대 코드를 입력해 합류하면, 같은 링크와 통계를 조회할 수 있습니다.

---

## 주요 기능

- **리다이렉트**: 단축 URL 접속 시 원본 URL로 이동, 만료·삭제된 링크는 차단 (로그인 불필요)
- **소셜 로그인**: 카카오·구글·네이버 로그인, 회원 탈퇴
- **워크스페이스**: 가입 시 개인 워크스페이스 생성, 팀 워크스페이스 생성, 초대 코드로 합류, 관리자·멤버 권한
- **링크 관리**: 단축 URL 발급, 원본 URL·제목·만료일 수정, 삭제 (워크스페이스당 최대 100개)
- **링크 통계**: 일별 클릭·방문자, 유입 경로·기기·운영체제·브라우저·국가·지역·시간대 분포, 오늘 실시간 수치
- **대시보드·알림**: 링크 비교, 만료 임박·방문자 급증 알림

---

## Architecture

![Snipy Hybrid 아키텍처](https://raw.githubusercontent.com/wiki/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03/images/diagrams/hybrid.drawio.png)

| 영역 | 기술 |
|------|------|
| Frontend | React 18, Vite, Cloudscape |
| Backend | Spring Boot 4 (`redirect-service`, `management-service`), JPA |
| Database | MySQL 8.0 (prod: RDS Multi-AZ), Redis (prod: ElastiCache) |
| 클릭 로그·집계 | dev는 Fluent Bit → SeaweedFS → DuckDB, prod는 Firehose → S3 → Athena |
| Infra | dev는 온프렘 kubeadm 클러스터, prod는 AWS EKS·CloudFront·WAF·ALB (Terraform) |
| CI/CD | GitHub Actions, Argo CD (GitOps), Argo Rollouts (카나리) |
| 모니터링 | Prometheus, Grafana, Alertmanager → Slack |
| 인증 | 카카오·구글·네이버 OAuth2 + JWT |

<details>
<summary>dev (온프렘) 세부 구성</summary>

![dev 아키텍처](https://raw.githubusercontent.com/wiki/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03/images/diagrams/dev.drawio.png)
</details>

<details>
<summary>prod (AWS) 세부 구성</summary>

![prod 아키텍처](https://raw.githubusercontent.com/wiki/Team-likelion-3rd-Project/likelion-devops-7th-3rd-team03/images/diagrams/prod.drawio.png)
</details>

---

## Core Design

- **리다이렉트 응답 속도** — 원본 URL을 Redis에 캐시하고, 클릭 기록은 302 응답 이후 별도 스레드에서 처리합니다. 기록이 밀리거나 실패해도 응답 시간에는 영향이 없습니다. (2차부터 유지)
- **잠정 통계와 확정 통계 분리** — 오늘 클릭 수는 Redis의 빠른 확인용 값이고, 확정 통계는 다음 날 배치가 원본 클릭 로그 전체를 다시 집계해 만듭니다. (2차부터 유지)
- **클릭 로그 저장 방식** — 원본 클릭 로그는 S3(prod)·SeaweedFS(dev)에 적재하고, Athena·DuckDB로 집계한 결과만 MySQL에 저장합니다.
- **워크스페이스 단위 링크 소유** — 링크를 사용자가 아닌 워크스페이스가 소유해, 담당자가 바뀌거나 팀을 나가도 링크와 통계가 유지됩니다. 2차의 JWT 클레임 기반 소유권 검증은 워크스페이스 멤버 확인으로 바뀌었습니다.
- **dev·prod 배포 방식 통일** — 두 환경 모두 같은 이미지를 Argo CD로 동기화하고, 카나리 배포 중 k6 검증에 실패하면 자동 롤백합니다.

---

## 제공 범위

**제공**
- 카카오·구글·네이버 소셜 로그인과 회원 탈퇴
- 워크스페이스 기반 링크 공동 관리와 관리자·멤버 권한
- 단축 URL 발급·수정·삭제와 리다이렉트
- 오늘 실시간 수치와 전날까지의 확정 통계, 만료 임박·방문자 급증 알림

**미제공**
- 브랜드 커스텀 도메인 연결, 기업용 단일 로그인(SSO)
- 악성 URL 자동 차단
- 시·군·구 단위 상세 지역 분석

---

## 역할 분담

- **한소현** (`Cloud`)
  - 3차: Terraform·EKS 기반 prod 구축, 배포 자동화, 부하 테스트
  - 2차: 로그인, 인프라, 배포
- **박장현** (`On-Prem · Frontend`)
  - 3차: kubeadm 클러스터 구축, 백업·노드 장애 대응, 대시보드 화면
  - 2차: 통계 API, 대시보드, 화면
- **유승미** (`Backend · Data`)
  - 3차: 클릭 수집·집계 파이프라인, 통계·알림 API, 카나리 배포
  - 2차: 통계 API, 배치
- **정예준** (`Backend · Docs`)
  - 3차: 워크스페이스 링크 API, 위키 문서 작성
  - 2차: 링크 CRUD, 리다이렉트, 부하 테스트

---

## 프로젝트 일정

| 기간 | 내용 |
|------|------|
| ~ 2026-08 | 2차 프로젝트, AWS에서 링크 단축·리다이렉트·통계 구현 |
| ~ 10/09 | 설계·기획 |
| ~ 10/23 | 온프렘(dev) 클러스터 구축과 CI/CD |
| ~ 11/06 | AWS(prod) 구성, Terraform EKS, 운영 첫 배포 (11/06) |
| ~ 11/13 | 장애·성능 시나리오 검증, 발표 |

---

## Documentation

상세 설계와 검증 기록은 [GitHub Wiki](../../wiki)에서 관리합니다.

- **Start Here**: [기획 배경](../../wiki/기획-배경) · [User Flows](../../wiki/User-Flows) · [UI Screens](../../wiki/UI-Screens)
- **아키텍처·설계**: [System Architecture](../../wiki/System-Architecture) · [ADR](../../wiki/ADR) · [ERD](../../wiki/ERD) · [API Specification](../../wiki/API-Specification)
- **운영**: [CI/CD](../../wiki/CI-CD) · [배포 가이드](../../wiki/배포-가이드) · [장애 시나리오](../../wiki/장애-시나리오) · [성능 테스트](../../wiki/성능-테스트) · [트러블슈팅](../../wiki/트러블슈팅)

---

## 로컬 실행

Docker와 Docker Compose가 필요합니다.

```bash
cp .env.example .env    # 비밀번호 등 필요한 값을 입력합니다
docker compose up -d
```

MySQL, Redis, 마이그레이션, 두 백엔드 서비스, 프론트엔드, Prometheus, Grafana, SeaweedFS, Fluent Bit가 실행됩니다. 집계 배치는 별도로 실행합니다.

```bash
docker compose --profile batch run --rm batch
```

- frontend: `http://localhost:3000`
- management-service: `http://localhost:8080`
- redirect-service (단축 URL): `http://localhost:8081`
- Grafana: `http://localhost:3001`

프론트엔드 코드 검사:

```bash
cd src/frontend && npm run lint && npm run format:check && npm run build
```

---

## 기여 방법

규칙 요약은 [CONTRIBUTING.md](./CONTRIBUTING.md)에, 이슈 생성부터 PR 머지까지의 절차는 [협업 가이드](./docs/GUIDE.md)에 있습니다.

## License

이 프로젝트는 [MIT License](./LICENSE)를 따릅니다.
