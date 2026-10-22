package redirect_service.clicklog.processor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import redirect_service.clicklog.event.ClickEvent;
import redirect_service.config.RealtimeStatsRecorderProperties;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RealtimeStatsRecorderTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RealtimeStatsRecorder recorder = new RealtimeStatsRecorder(
            redisTemplate, new RealtimeStatsRecorderProperties(), new SimpleMeterRegistry());

    @Test
    void 종료할_때_버퍼에_남은_이벤트를_Redis에_반영한다() {
        recorder.start();
        recorder.onClick(click());   // 단위 테스트에서는 @Async 없이 바로 버퍼에 들어간다

        recorder.stop();

        verify(redisTemplate).executePipelined(any(SessionCallback.class));
        assertThat(recorder.isRunning()).isFalse();
    }

    @Test
    void 버퍼가_비어_있으면_종료할_때_Redis를_호출하지_않는다() {
        recorder.start();

        recorder.stop();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void Redis_연결보다_먼저_멈춘다() {
        // SmartLifecycle 은 phase 가 큰 것부터 멈춘다. Lettuce 의 phase 가 바뀌면 이 테스트가 알려준다
        assertThat(recorder.getPhase()).isGreaterThan(new LettuceConnectionFactory().getPhase());
    }

    private static ClickEvent click() {
        return new ClickEvent(UUID.randomUUID(), 1, 1L, LocalDateTime.now(), "visitor-1",
                "ko-KR", "Mozilla/5.0", "DESKTOP", "Windows", "Chrome", null, "DIRECT", false);
    }
}