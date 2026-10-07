package redirect_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 리다이렉트 캐시와 방문자 쿠키 정책이다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.redirect")
public class RedirectProperties {

    private Duration cacheTtl = Duration.ofMinutes(10);
    private String visitorCookieName = "visitor_id";
    private Duration visitorCookieMaxAge = Duration.ofDays(365);
    private boolean visitorCookieSecure = true;
}
