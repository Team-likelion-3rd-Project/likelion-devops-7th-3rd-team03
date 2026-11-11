# 롤링 배포 중 요청 실패·클릭 유실 측정

redirect-service 파드를 교체(`rollout restart`)하는 동안 단축 URL 클릭을 계속 보내,
교체 중에 요청이 끊기는지(요청 실패)와 클릭 로그가 사라지는지(클릭 유실)를 잰다. (BE5)

## 결과 (2026-10-27, 온프렘 dev)

| 회차 | 교체 시간 | 교체 중 요청 | 요청 실패 | 클릭 (보냄 → 집계) |
|---|---|---|---|---|
| 1 | 92초 | 361 | 0 / 900 | 901 → 901 |
| 2 | 81초 | 315 | 0 / 900 | 901 → 901 |
| 3 | 111초 | 433 | 0 / 900 | 901 → 901 |

**3회 모두 요청 실패 0, 클릭 유실 0.** BE5 완료 기준("롤링 배포 중 요청 실패 0")을 이 조건에서 만족한다.

### 조건

| 항목 | 값 |
|---|---|
| 클러스터 | 온프렘 kubeadm (워커 2대, 각 2코어) |
| redirect 레플리카 | 2 |
| 롤링 설정 | maxSurge 25%, maxUnavailable 25% (레플리카 2개면 1개 / 0개 → 새 파드를 먼저 띄우고 옛 파드를 내림) |
| 종료 대기 | `terminationGracePeriodSeconds: 60` (Fluent Bit 사이드카 Grace 30초) |
| 부하 | 약 4건/초, 회차당 900건 (curl, cp1에서) |
| 클릭 계산 | 보낸 302 수 + 확인용 1건 = 901 |

### 왜 유실이 없었나

옛 파드가 종료 신호를 받으면 Fluent Bit 사이드카가 버퍼에 남은 클릭 로그를 SeaweedFS에 올리고 꺼진다.
1회차에서 옛 파드 종료(07:17:46)와 마지막 업로드(07:17:47)가 1초 차이였다.

### 한계

- **부하가 작다** (약 4건/초). 노트북 VM 자원이 빠듯해 더 올리지 못했다. 부하가 크면 종료 순간 처리 중인 요청이 많아져 결과가 달라질 수 있다. 카나리 작업에서 k6로 다시 잰다.
- **정상 교체만 봤다.** 파드가 갑자기 죽는 경우(OOMKilled, 노드 다운)는 Fluent Bit이 버퍼를 올릴 시간이 없어 유실될 수 있다.
- redirect-service만 측정했다.

## 측정 방법

cp1에서 실행한다.

### 1. 함수 등록

```bash
ROOT_PW=$(kubectl -n snipy get secret mysql-secrets -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)
URL=http://192.168.122.21:30081
UA="Mozilla/5.0 Chrome/130.0"

# 테스트 사용자 (한 번만)
kubectl -n snipy exec mysql-0 -- mysql -uroot -p"$ROOT_PW" shortlink -e "
  INSERT IGNORE INTO users (user_id, provider, social_id, nickname, email)
  VALUES (UUID(), 'KAKAO', 'loss-test', 'loss', 'loss@example.com');" 2>&1 | grep -v Warning

run_loss() {
  local SLUG=$1 LOG=/tmp/$1.log RO=/tmp/$1.rollout
  kubectl -n snipy exec mysql-0 -- mysql -uroot -p"$ROOT_PW" shortlink -e "
    INSERT INTO links (link_id, user_id, slug, title, original_url)
    SELECT UUID(), id, '$SLUG', 'loss test', 'https://example.com' FROM users WHERE social_id='loss-test';" 2>&1 | grep -v Warning
  echo "[$SLUG] 확인 클릭: $(curl -s -o /dev/null -w '%{http_code}' -A "$UA" $URL/$SLUG)"

  : > $LOG; : > $RO
  ( sleep 30
    echo "교체 시작 $(date +%T)" >> $RO
    kubectl -n snipy rollout restart deploy/redirect-service >/dev/null
    kubectl -n snipy rollout status deploy/redirect-service --timeout=10m >/dev/null
    echo "교체 끝 $(date +%T)" >> $RO ) &

  for i in $(seq 1 900); do
    code=$(curl -s -o /dev/null -m 3 -w "%{http_code}" -A "$UA" $URL/$SLUG)
    echo "$(date +%T) $code" >> $LOG
    sleep 0.2
  done
  wait

  local S=$(sed -n 's/교체 시작 //p' $RO) E=$(sed -n 's/교체 끝 //p' $RO)
  echo "===== [$SLUG]"
  echo "측정: $(head -1 $LOG | cut -d' ' -f1) ~ $(tail -1 $LOG | cut -d' ' -f1)"
  echo "교체: $S ~ $E"
  echo "교체 중 요청: $(awk -v s=$S -v e=$E '$1>=s && $1<=e' $LOG | wc -l)"
  echo "응답 코드:"; awk '{print $2}' $LOG | sort | uniq -c
}
```

### 2. 측정 (회차마다 슬러그를 바꾼다)

```bash
run_loss loss01
run_loss loss02
run_loss loss03
```

교체 시작·끝이 측정 시간 안에 들어가야 한다. 교체가 측정보다 늦게 끝나면 그 회차는 다시 한다.
`302`가 아닌 응답(`000` 연결 실패, `502`, `503` 등)이 **요청 실패**다.

### 3. 클릭 집계 (마지막 회차 끝나고 6분 뒤)

Fluent Bit이 5분마다 SeaweedFS에 올리므로 6분 기다린 뒤 배치를 돌린다.

```bash
D=$(TZ=Asia/Seoul date +%F)
kubectl -n snipy delete job loss-batch --ignore-not-found
kubectl -n snipy create job loss-batch --from=cronjob/click-stats-batch --dry-run=client -o json \
  | python3 -c "import sys,json; j=json.load(sys.stdin); j['spec']['template']['spec']['containers'][0]['args']=['$D']; print(json.dumps(j))" \
  | kubectl apply -f -
kubectl -n snipy wait --for=condition=complete job/loss-batch --timeout=300s

kubectl -n snipy exec mysql-0 -- mysql -uroot -p"$ROOT_PW" shortlink -e "
SELECT l.slug, s.click_count FROM link_daily_stats s JOIN links l ON l.id=s.link_id
WHERE s.stat_date='$D' AND l.slug LIKE 'loss%' ORDER BY l.slug;" 2>&1 | grep -v Warning
```

`click_count`가 회차마다 **901**(900 + 확인용 1)이면 유실 0이다.
`link_daily_stats.link_id`는 `links.id`(숫자)를 가리킨다 (`links.link_id`는 UUID).

## 측정 전 확인

- `kubectl -n snipy get pods`에서 redirect가 `2/2`, 기동이 1~2분 안이어야 한다. 기동이 느리면 교체 시간이 길어져 결과가 왜곡된다 ([Argo CD README 문제 해결](../infra/onprem/argocd/README.md) 참고).
- 호스트에서 Docker Desktop(WSL2)을 꺼 둔다. 워커 CPU가 포화되면 기동이 3~5분으로 늘어난다.