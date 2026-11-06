package redirect_service.redirect;

import redirect_service.redirect.RedirectTarget;

import java.time.LocalDateTime;

public record RedirectCacheEntry(
        Long linkId,
        String originalUrl,
        boolean isEnabled,
        LocalDateTime expiresAt
) {

    public boolean isRedirectable(LocalDateTime now) {
        return isEnabled && (expiresAt == null || now.isBefore(expiresAt));
    }

    public RedirectTarget toRedirectTarget() {
        return new RedirectTarget(linkId, originalUrl);
    }
}
