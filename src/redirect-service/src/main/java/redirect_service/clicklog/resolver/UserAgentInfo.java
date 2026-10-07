package redirect_service.clicklog.resolver;

public record UserAgentInfo(
        String deviceType,
        String operatingSystem,
        String browser
) {
}
