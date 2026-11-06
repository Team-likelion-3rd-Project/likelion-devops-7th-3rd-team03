package redirect_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 클릭 이벤트 조립 시 신뢰할 프록시 헤더 정책이다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.click-event")
public class ClickEventProperties {

    private boolean trustForwardedFor = true;
}
