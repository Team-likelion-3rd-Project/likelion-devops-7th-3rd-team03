package redirect_service.clicklog.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import redirect_service.clicklog.resolver.BotResolver;
import redirect_service.clicklog.resolver.ReferrerCategoryResolver;
import redirect_service.clicklog.resolver.UserAgentInfo;
import redirect_service.clicklog.resolver.UserAgentResolver;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 클릭 이벤트 경로를 미리 데운다 (#273). RedirectWarmup 이 302 경로를 데우기 전에 부른다.
 *
 * #264 워밍업은 302 경로만 데우고 클릭 이벤트는 발행하지 않는다(가짜 클릭이 통계에 섞이지 않게).
 * 그래서 배포 직후 모든 파드가 처음 상태에서 급증이 오면, 첫 실제 클릭들이 UA 분석·직렬화·Firehose 첫 연결을
 * 한꺼번에 처음 실행해 CPU 가 몰렸다 (바이럴 800 재테스트에서 시작 24초 p99 3.6s).
 *
 * 여기서는 클릭 이벤트를 만들거나 보내지 않고 경로만 실행한다.
 * - UA 분석기: Yauaa preheat + 대표 UA 로 resolve
 * - 봇·유입 판별, 클릭 이벤트 JSON 직렬화: 결과는 버린다
 * - Firehose: 레코드 0개 PutRecordBatch. 서버가 검증 오류로 거절해 데이터는 쓰지 않지만,
 *   자격 증명(IRSA → STS)과 TLS 연결은 이 호출에서 맺어진다. redirect 의 IAM 권한은 PutRecord·PutRecordBatch 뿐이다
 */
@Slf4j
@Component
public class ClickPathWarmup {

    private static final List<String> SAMPLE_USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
    private static final List<String> SAMPLE_REFERRERS = List.of(
            "https://www.google.com/search?q=snipy", "https://www.instagram.com/", "https://t.co/abc", "");

    private final UserAgentResolver userAgentResolver;
    private final BotResolver botResolver;
    private final ReferrerCategoryResolver referrerCategoryResolver;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<FirehoseClient> firehoseClient;   // 온프렘(sink=log)에는 없다
    private final String streamName;

    public ClickPathWarmup(UserAgentResolver userAgentResolver,
                           BotResolver botResolver,
                           ReferrerCategoryResolver referrerCategoryResolver,
                           JsonMapper jsonMapper,
                           ObjectProvider<FirehoseClient> firehoseClient,
                           @Value("${app.click-event.firehose-stream-name:}") String streamName) {
        this.userAgentResolver = userAgentResolver;
        this.botResolver = botResolver;
        this.referrerCategoryResolver = referrerCategoryResolver;
        this.jsonMapper = jsonMapper;
        this.firehoseClient = firehoseClient;
        this.streamName = streamName;
    }

    public void warmUp(int iterations) {
        long startedAt = System.nanoTime();

        userAgentResolver.preheat();
        for (int i = 0; i < iterations; i++) {
            String userAgent = SAMPLE_USER_AGENTS.get(i % SAMPLE_USER_AGENTS.size());
            String referrer = SAMPLE_REFERRERS.get(i % SAMPLE_REFERRERS.size());
            UserAgentInfo info = userAgentResolver.resolve(userAgent);
            boolean bot = botResolver.isBot(userAgent);
            String category = referrerCategoryResolver.resolve(referrer);
            jsonMapper.writeValueAsString(new ClickEvent(UUID.randomUUID(), 2, 0L, LocalDateTime.now(), "warmup",
                    "ko-KR", userAgent, info.deviceType(), info.operatingSystem(), info.browser(),
                    referrer, category, bot, "KR", null));
        }

        boolean firehoseWarmed = warmFirehose();
        log.info("클릭 경로 워밍업 완료. iterations={}, firehose={}, elapsedMs={}",
                iterations, firehoseWarmed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private boolean warmFirehose() {
        FirehoseClient client = firehoseClient.getIfAvailable();
        if (client == null || streamName.isBlank()) {
            return false;
        }
        try {
            client.putRecordBatch(b -> b.deliveryStreamName(streamName).records(List.of()));
        } catch (SdkException expected) {
            // 레코드 0개라 거절되는 것이 정상이다. 연결·자격 증명은 이미 맺어졌다
            log.debug("Firehose 워밍업 호출 거절 (예상됨): {}", expected.getMessage());
        }
        return true;
    }
}