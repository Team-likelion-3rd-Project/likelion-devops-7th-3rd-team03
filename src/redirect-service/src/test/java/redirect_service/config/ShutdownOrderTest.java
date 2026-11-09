package redirect_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import redirect_service.clicklog.processor.FirehoseClickEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 순서 (#263). phase 가 큰 것부터 멈춘다.
 * 웹 서버 정지(DEFAULT_PHASE - 2048) → 조립 → 로그·실시간 → Firehose 전송기 → 실시간 flush(1024)
 */
class ShutdownOrderTest {

    private static final int WEB_SERVER_STOP_PHASE = SmartLifecycle.DEFAULT_PHASE - 2048;
    private static final int REALTIME_STATS_FLUSH_PHASE = 1024;

    @Test
    void 웹서버가_멈춘_뒤_조립_로그_전송기_순서로_멈춘다() {
        assertThat(AsyncEventConfig.ASSEMBLER_PHASE).isLessThan(WEB_SERVER_STOP_PHASE);
        assertThat(AsyncEventConfig.CONSUMER_PHASE).isLessThan(AsyncEventConfig.ASSEMBLER_PHASE);
        assertThat(FirehoseClickEventPublisher.PHASE).isLessThan(AsyncEventConfig.CONSUMER_PHASE);
        assertThat(FirehoseClickEventPublisher.PHASE).isGreaterThan(REALTIME_STATS_FLUSH_PHASE);
    }
}