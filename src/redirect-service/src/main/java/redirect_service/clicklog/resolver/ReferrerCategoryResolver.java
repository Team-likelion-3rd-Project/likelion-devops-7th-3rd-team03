package redirect_service.clicklog.resolver;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class ReferrerCategoryResolver {

    public String resolve(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return "DIRECT";
        }

        String host = extractHost(referrer);
        if (host == null) {
            return "ETC";
        }
        if (host.contains("instagram.")) return "INSTAGRAM";
        if (host.contains("facebook.")) return "FACEBOOK";
        if (host.contains("naver.")) return "NAVER";
        if (host.contains("google.")) return "GOOGLE";
        if (host.contains("daum.") || host.contains("kakao.")) return "KAKAO";
        if (host.equals("t.co") || host.contains("twitter.") || host.equals("x.com") || host.endsWith(".x.com")) return "X";
        return "ETC";
    }

    private String extractHost(String referrer) {
        try {
            String host = URI.create(referrer).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
