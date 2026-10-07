package redirect_service.clicklog.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.resolver.BotResolver;
import redirect_service.clicklog.event.ClickEvent;
import redirect_service.clicklog.event.ClickRequestSnapshot;
import redirect_service.clicklog.event.RedirectSucceededEvent;
import redirect_service.clicklog.resolver.ReferrerCategoryResolver;
import redirect_service.clicklog.resolver.UserAgentInfo;
import redirect_service.clicklog.resolver.UserAgentResolver;

import java.util.UUID;

/** 원본 리다이렉트 이벤트를 소비자 공용의 완성된 클릭 이벤트로 변환한다. */
@Component
@RequiredArgsConstructor
public class ClickEventAssembler {

    private static final int CLICK_EVENT_SCHEMA_VERSION = 1;
    private static final int USER_AGENT_MAX_LENGTH = 500;
    private static final int REFERRER_MAX_LENGTH = 500;
    private static final int LANGUAGE_MAX_LENGTH = 50;

    private final BotResolver botResolver;
    private final UserAgentResolver userAgentResolver;
    private final ReferrerCategoryResolver referrerCategoryResolver;
    private final ApplicationEventPublisher eventPublisher;

    @Async("clickEventAssemblerExecutor")
    @EventListener
    public void onRedirectSucceeded(RedirectSucceededEvent event) {
        ClickRequestSnapshot request = event.request();
        String userAgent = truncate(request.userAgent(), USER_AGENT_MAX_LENGTH);
        String referrer = truncate(request.referrer(), REFERRER_MAX_LENGTH);
        UserAgentInfo userAgentInfo = userAgentResolver.resolve(userAgent);

        eventPublisher.publishEvent(new ClickEvent(
                UUID.randomUUID(),
                CLICK_EVENT_SCHEMA_VERSION,
                event.linkId(),
                request.occurredAt(),
                request.visitorId(),
                resolveLanguage(request.acceptLanguage()),
                userAgent,
                userAgentInfo.deviceType(),
                userAgentInfo.operatingSystem(),
                userAgentInfo.browser(),
                referrer,
                referrerCategoryResolver.resolve(referrer),
                botResolver.isBot(userAgent)
        ));
    }

    private String resolveLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        String primaryLanguage = acceptLanguage.split(",", 2)[0].split(";", 2)[0].trim();
        return truncate(primaryLanguage, LANGUAGE_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
