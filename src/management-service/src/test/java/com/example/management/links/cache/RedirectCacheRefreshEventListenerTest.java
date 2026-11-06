package com.example.management.links.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedirectCacheRefreshEventListenerTest {

    @Mock
    private RedisRedirectCacheWriter cacheWriter;

    @Test
    void writesTheCommittedSnapshot() {
        RedirectCacheEntry entry = new RedirectCacheEntry(
                7L, "Ab3f2Xz", "https://example.com", true, null);
        RedirectCacheRefreshEventListener listener = new RedirectCacheRefreshEventListener(cacheWriter);

        listener.refreshAfterCommit(new RedirectCacheRefreshEvent(entry));

        verify(cacheWriter).put(entry);
    }
}
