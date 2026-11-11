#!/usr/bin/env bash
# drain·재부팅 시험 중에 상태가 바뀐 시각을 자동으로 기록한다. 읽기만 하고 클러스터를 바꾸지 않는다.
# cp1 에서 root 로 실행하고, 시험이 끝나면 Ctrl+C 로 멈춘다.
#   bash measure.sh <확인할 URL> [<URL> ...]
#   예) bash measure.sh http://192.168.122.11:30080/
# 로그는 /tmp/measure-<시각>/ 에 쌓인다 (시각은 모두 UTC).
#   http.log   URL 별로 HTTP 코드가 바뀐 시각 (000 은 연결 실패). 200 이 아닌 구간이 서비스 중단이다.
#   state.log  노드·파드(Completed 제외)의 READY/STATUS 가 바뀐 시각 (바뀐 줄만 < 이전, > 이후)
#   top.log    30초마다 kubectl top nodes
set -u
[ "$#" -ge 1 ] || { echo "사용법: bash $0 <URL> [<URL> ...]"; exit 1; }
OUT="/tmp/measure-$(date -u +%H%M%S)"
mkdir -p "$OUT"
ts() { date -u +%T; }

# 1) 각 URL 의 HTTP 코드가 바뀔 때만 한 줄 기록
(
  declare -A last
  while :; do
    for u in "$@"; do
      c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$u")"
      if [ "$c" != "${last[$u]:-}" ]; then echo "$(ts) $c $u" >> "$OUT/http.log"; last[$u]="$c"; fi
    done
    sleep 1
  done
) &

# 2) 노드·파드 상태가 바뀐 줄만 기록
(
  prev=""
  while :; do
    cur="$( { kubectl get nodes --no-headers | awk '{print "node", $1, $2}'
              kubectl get pods -A --no-headers | awk '$4 != "Completed" {print $1 "/" $2, $3, $4}'; } 2>/dev/null | sort )"
    if [ -n "$cur" ] && [ "$cur" != "$prev" ]; then
      { echo "== $(ts)"; diff <(echo "$prev") <(echo "$cur") | grep '^[<>]'; } >> "$OUT/state.log"
      prev="$cur"
    fi
    sleep 2
  done
) &

# 3) 노드 자원 30초마다
(
  while :; do { echo "== $(ts)"; kubectl top nodes --no-headers 2>/dev/null; } >> "$OUT/top.log"; sleep 30; done
) &

trap 'kill $(jobs -p) 2>/dev/null; echo; echo "기록 위치: $OUT"' EXIT INT TERM
echo "측정 시작 $(ts) UTC -> $OUT (끝내려면 Ctrl+C)"
wait
