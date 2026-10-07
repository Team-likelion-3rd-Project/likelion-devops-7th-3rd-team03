package redirect_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Redis 잠정 통계 recorder의 보관·배치·버퍼 정책이다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.realtime-stats")
public class RealtimeStatsRecorderProperties {

    private Duration ttl = Duration.ofDays(3); // retention
    private Duration flushInterval = Duration.ofSeconds(5);
    private int bufferCapacity = 100_000;
}
