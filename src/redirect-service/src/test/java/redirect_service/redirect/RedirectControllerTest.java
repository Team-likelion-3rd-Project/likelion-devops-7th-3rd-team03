package redirect_service.redirect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import redirect_service.config.RedirectProperties;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedirectControllerTest {

    @Test
    @DisplayName("서비스가 반환한 URL을 Location 헤더에 담아 302를 반환한다")
    void redirectsToUrlReturnedByService() {
        RedirectService redirectService = mock(RedirectService.class);
        RedirectProperties properties = new RedirectProperties();
        VisitorIdResolver visitorIdResolver = mock(VisitorIdResolver.class);
        RedirectController controller = new RedirectController(redirectService, properties, visitorIdResolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "Mozilla/5.0");
        when(visitorIdResolver.resolve(null)).thenReturn("visitor-id");
        when(visitorIdResolver.needsCookie(null)).thenReturn(true);
        when(visitorIdResolver.setCookieHeader("visitor-id"))
                .thenReturn("visitor_id=visitor-id; Path=/");
        when(redirectService.redirect(org.mockito.ArgumentMatchers.any(RedirectRequest.class)))
                .thenReturn(new RedirectTarget(1L, "https://example.com/page"));

        var response = controller.redirect("abc123", request);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("https://example.com/page"));
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("visitor_id=visitor-id");
        org.mockito.ArgumentCaptor<RedirectRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(RedirectRequest.class);
        verify(redirectService).redirect(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(RedirectRequest::slug, RedirectRequest::visitorId,
                        RedirectRequest::remoteAddress, RedirectRequest::userAgent)
                .containsExactly("abc123", "visitor-id", "203.0.113.10", "Mozilla/5.0");
        assertThat(requestCaptor.getValue().occurredAt()).isNotNull();
    }
}
