package redirect_service.clicklog.resolver;

import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class UserAgentResolver {

    private final UserAgentAnalyzer analyzer = UserAgentAnalyzer.newBuilder()
            .hideMatcherLoadStats()
            .withField("DeviceClass")
            .withField("OperatingSystemName")
            .withField("AgentName")
            .withCache(1_000)
            .build();

    public UserAgentInfo resolve(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new UserAgentInfo("UNKNOWN", "UNKNOWN", "UNKNOWN");
        }

        var parsedUserAgent = analyzer.parse(userAgent);
        return new UserAgentInfo(
                resolveDeviceType(parsedUserAgent.getValue("DeviceClass")),
                resolveValue(parsedUserAgent.getValue("OperatingSystemName")),
                resolveValue(parsedUserAgent.getValue("AgentName"))
        );
    }

    private String resolveDeviceType(String deviceClass) {
        if (deviceClass == null) {
            return "UNKNOWN";
        }
        return switch (deviceClass.toUpperCase(Locale.ROOT)) {
            case "PHONE", "MOBILE" -> "MOBILE";
            case "TABLET" -> "TABLET";
            case "DESKTOP" -> "DESKTOP";
            default -> "UNKNOWN";
        };
    }

    private String resolveValue(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
