#!/bin/sh
# 메모리 실험용: 배치를 실행하고 종료 코드, 소요 시간, 컨테이너 최대 메모리를 출력한다
START=$(date +%s)
java -jar /app/app.jar "$1" > /tmp/batch.log 2>&1
CODE=$?
END=$(date +%s)
PEAK=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)
echo "EXIT=$CODE SEC=$((END - START)) PEAK_MB=$((PEAK / 1048576))"
grep -E "UPSERT|ALERT|Out of Memory|OutOfMemoryError" /tmp/batch.log | tail -3