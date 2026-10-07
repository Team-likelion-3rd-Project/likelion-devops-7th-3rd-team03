package redirect_service.clicklog.resolver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickRequestSnapshot;
import redirect_service.config.ClickEventProperties;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final ClickEventProperties properties;

    public String resolve(ClickRequestSnapshot request) {
        if (properties.isTrustForwardedFor()) {
            String forwardedFor = request.forwardedFor();
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",", 2)[0].trim();
            }
        }
        return request.remoteAddress();
    }
}
