package redirect_service.redirect;

import java.time.LocalDateTime;

/** 리다이렉트 처리와 비동기 클릭 이벤트에 필요한 요청 정보의 불변 복사본이다. */
public record RedirectRequest(
        String slug,
        String visitorId,
        LocalDateTime occurredAt,
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String referrer,
        String acceptLanguage
) {
}
