package redirect_service.clicklog.event;

import redirect_service.redirect.RedirectRequest;

import java.time.LocalDateTime;

/** 비동기 클릭 처리에 필요한 HTTP 요청 정보의 불변 복사본이다. */
public record ClickRequestSnapshot(
        String visitorId,
        LocalDateTime occurredAt,
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String referrer,
        String acceptLanguage
) {
    public static ClickRequestSnapshot from(RedirectRequest request) {
        return new ClickRequestSnapshot(
                request.visitorId(),
                request.occurredAt(),
                request.remoteAddress(),
                request.forwardedFor(),
                request.userAgent(),
                request.referrer(),
                request.acceptLanguage()
        );
    }
}
