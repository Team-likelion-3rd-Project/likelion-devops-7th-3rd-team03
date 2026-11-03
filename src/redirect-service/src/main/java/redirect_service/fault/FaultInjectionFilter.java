package redirect_service.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 카나리 자동 롤백 시험용 장애 주입. 운영에서 쓰지 않는다.
 *
 * app.fault-injection.enabled=true 일 때만 Bean 이 만들어진다 (값이 없거나 true 가 아니면 만들어지지 않는다).
 * 기존 app.click-event.sink, app.batch.engine 처럼 설정값으로 켜고 끈다.
 *
 * 켜면 요청의 error-rate 비율만큼 500 을 돌려준다. 카나리 파드에만 이 값을 넣어 "에러 나는 새 버전"을 흉내 내고,
 * k6 분석이 에러율 기준을 넘겨 Rollout 이 자동으로 abort 되는지 확인한다.
 * /actuator 는 제외해 probe 는 통과시킨다 (파드는 살아 있고 요청만 실패하는 상황을 만든다).
 */
@Component
@ConditionalOnProperty(name = "app.fault-injection.enabled", havingValue = "true")
class FaultInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private final double errorRate;

    FaultInjectionFilter(@Value("${app.fault-injection.error-rate:0.5}") double errorRate) {
        this.errorRate = errorRate;
        log.warn("장애 주입이 켜져 있습니다 (시험용). 요청의 {}% 를 500 으로 응답합니다.", (int) (errorRate * 100));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ThreadLocalRandom.current().nextDouble() < errorRate) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "fault injection");
            return;
        }
        chain.doFilter(request, response);
    }
}