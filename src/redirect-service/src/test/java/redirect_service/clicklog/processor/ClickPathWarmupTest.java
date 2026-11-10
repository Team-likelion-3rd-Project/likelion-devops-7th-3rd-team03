package redirect_service.clicklog.processor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import redirect_service.clicklog.resolver.BotResolver;
import redirect_service.clicklog.resolver.ReferrerCategoryResolver;
import redirect_service.clicklog.resolver.UserAgentInfo;
import redirect_service.clicklog.resolver.UserAgentResolver;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClickPathWarmupTest {

    private final UserAgentResolver userAgentResolver = mock(UserAgentResolver.class);
    private final BotResolver botResolver = mock(BotResolver.class);
    private final ReferrerCategoryResolver referrerResolver = mock(ReferrerCategoryResolver.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final FirehoseClient firehose = mock(FirehoseClient.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<FirehoseClient> provider(FirehoseClient client) {
        ObjectProvider<FirehoseClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private ClickPathWarmup warmup(FirehoseClient client) {
        when(userAgentResolver.resolve(anyString())).thenReturn(new UserAgentInfo("DESKTOP", "Windows", "Chrome"));
        when(referrerResolver.resolve(anyString())).thenReturn("SEARCH");
        return new ClickPathWarmup(userAgentResolver, botResolver, referrerResolver, jsonMapper, provider(client), "stream");
    }

    @Test
    void UA_분석기를_preheat하고_대표_UA로_여러_번_돌린다() {
        warmup(null).warmUp(10);

        verify(userAgentResolver).preheat();
        verify(userAgentResolver, times(10)).resolve(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void Firehose가_있으면_레코드_없는_요청으로_연결만_맺고_거절은_무시한다() {
        when(firehose.putRecordBatch(any(Consumer.class))).thenThrow(SdkClientException.create("no records"));

        assertThatCode(() -> warmup(firehose).warmUp(1)).doesNotThrowAnyException();

        verify(firehose).putRecordBatch(any(Consumer.class));
    }

    @Test
    void 온프렘처럼_Firehose가_없으면_부르지_않는다() {
        assertThatCode(() -> warmup(null).warmUp(1)).doesNotThrowAnyException();
    }
}