package com.example.management.links.cache;

/**
 * 트랜잭션 안에서는 DB 변경 사실만 전달하고, Redis 갱신은 커밋 성공 뒤에 수행하기 위한 이벤트다.
 */
public record RedirectCacheRefreshEvent(RedirectCacheEntry entry) {
}
