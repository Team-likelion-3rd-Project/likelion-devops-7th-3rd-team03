package redirect_service.clicklog.event;

import java.time.LocalDateTime;
import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** 클릭 로그 소비자가 공통으로 사용하는, 해석이 끝난 이벤트다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClickEvent(
        UUID eventId,
        int schemaVersion,
        Long linkId,
        LocalDateTime clickedAt,
        String visitorId,
        String language,
        String userAgent,
        String deviceType,
        String operatingSystem,
        String browser,
        String referrer,
        String referrerCategory,
        boolean isBot
) {
}
