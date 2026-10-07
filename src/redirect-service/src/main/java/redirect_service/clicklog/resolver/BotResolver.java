package redirect_service.clicklog.resolver;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 분석 집계에서 제외할 알려진 크롤러 User-Agent를 식별하는 1차 필터다. */
@Component
public class BotResolver {

    private static final Pattern BOT_USER_AGENT = Pattern.compile(
            "(?:bot|crawler|crawl|spider|slurp|archiver|facebookexternalhit|"
                    + "preview|telegrambot|discordbot|whatsapp|wget|curl|python-requests|okhttp)",
            Pattern.CASE_INSENSITIVE
    );

    public boolean isBot(String userAgent) {
        return userAgent != null
                && !userAgent.isBlank()
                && BOT_USER_AGENT.matcher(userAgent).find();
    }
}
