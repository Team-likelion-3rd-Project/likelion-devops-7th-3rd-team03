package redirect_service.redirect;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import redirect_service.config.RedirectProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService redirectService;
    private final RedirectProperties redirectProperties;
    private final VisitorIdResolver visitorIdResolver;

    @GetMapping("/{slug}")
    public ResponseEntity<Void> redirect(@PathVariable String slug, HttpServletRequest request) {
        String existingVisitorId = findCookieValue(request.getCookies(), redirectProperties.getVisitorCookieName());
        String visitorId = visitorIdResolver.resolve(existingVisitorId);
        RedirectRequest redirectRequest = new RedirectRequest(
                slug,
                visitorId,
                LocalDateTime.now(ZoneOffset.UTC),
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("User-Agent"),
                request.getHeader("Referer"),
                request.getHeader("Accept-Language")
        );
        RedirectTarget redirectTarget = redirectService.redirect(redirectRequest);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectTarget.originalUrl()));
        if (visitorIdResolver.needsCookie(existingVisitorId)) {
            response.header("Set-Cookie", visitorIdResolver.setCookieHeader(visitorId));
        }
        return response.build();
    }

    private String findCookieValue(Cookie[] cookies, String cookieName) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
