"""
배치 부하 테스트용 클릭 로그 생성기.
Fluent Bit이 SeaweedFS에 저장하는 것과 같은 형식(year=/month=/day=, 줄단위 JSON, gzip)으로 만든다.

사용: python gen_click_logs.py <출력폴더> <이벤트수> <링크수> <날짜 YYYY-MM-DD>
시각은 UTC 01:00~09:00 사이로 만들어 전부 같은 KST 날짜(10:00~18:00)에 들어가게 한다.
"""
import gzip
import json
import os
import random
import sys
import uuid
from datetime import datetime, timedelta

out_dir, total, link_count, day = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
year, month, date = day.split("-")
prefix = f"{out_dir}/year={year}/month={month}/day={date}"
os.makedirs(prefix, exist_ok=True)

base = datetime.fromisoformat(f"{day}T01:00:00")
per_file = 200_000
devices = ["DESKTOP", "MOBILE", "TABLET"]
referrers = ["DIRECT", "SEARCH", "SOCIAL"]
languages = ["ko-KR", "en-US", "ja-JP", None]

written = 0
file_index = 0
while written < total:
    count = min(per_file, total - written)
    with gzip.open(f"{prefix}/loadgen-{file_index:04d}.gz", "wt") as fp:
        for _ in range(count):
            clicked_at = base + timedelta(seconds=random.randint(0, 8 * 3600),
                                          microseconds=random.randint(0, 999_999))
            fp.write(json.dumps({
                "event_id": str(uuid.uuid4()),
                "schema_version": 1,
                "link_id": random.randint(1, link_count),
                "clicked_at": clicked_at.isoformat(),
                "visitor_id": str(uuid.uuid4()),
                "language": random.choice(languages),
                "user_agent": "loadgen",
                "device_type": random.choice(devices),
                "operating_system": "Linux",
                "browser": "Chrome",
                "referrer": None,
                "referrer_category": random.choice(referrers),
                "is_bot": False,
            }) + "\n")
    written += count
    file_index += 1
    print(f"{written}/{total}", flush=True)
