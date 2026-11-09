package redirect_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 기동 직후 JIT 워밍업 설정이다 (#264).
 * 로컬 측정에서 차가운 JVM은 요청 약 1.4만 건(60초)이 지나야 p99가 안정됐다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.warmup")
public class WarmupProperties {

    /** 테스트·로컬은 끈다. 클러스터는 Deployment env로 켠다 */
    private boolean enabled = false;
    private int requests = 15_000;
    private int concurrency = 8;
    /** 넘으면 남은 건수와 상관없이 끝내고 트래픽을 받는다 */
    private Duration maxDuration = Duration.ofSeconds(60);
    /**
     * 클릭 경로 워밍업 반복 횟수 (#273). UA 분석기는 preHeat 이 데우고(UserAgentResolver 는 결과를 캐시해 반복해도 분석은 처음 몇 번뿐),
     * 반복은 봇·유입 판별과 클릭 이벤트 직렬화를 데운다.
     */
    private int clickPathIterations = 500;
}