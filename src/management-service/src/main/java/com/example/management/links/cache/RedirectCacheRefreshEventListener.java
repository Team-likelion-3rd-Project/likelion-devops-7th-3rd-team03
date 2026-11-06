package com.example.management.links.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/** DB 커밋이 성공한 상태만 Redis에 반영한다. */
@Component
@RequiredArgsConstructor
public class RedirectCacheRefreshEventListener {

    private final RedisRedirectCacheWriter redisRedirectCacheWriter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refreshAfterCommit(RedirectCacheRefreshEvent event) {
        // 롤백된 링크를 캐시에 warm-up하거나 삭제 상태로 바꾸는 일을 방지한다.
        redisRedirectCacheWriter.put(event.entry());
    }
}
