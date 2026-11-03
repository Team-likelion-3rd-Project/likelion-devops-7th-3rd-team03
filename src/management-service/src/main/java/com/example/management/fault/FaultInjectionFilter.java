package com.example.management.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 카나리 자동 롤백 시험용 장애 주입. 운영에서 쓰지 않는다. (redirect 의 같은 필터와 동일한 방식)
 *
 * app.fault-injection.enabled=true 일 때만 Bean 이 만들어진다 (값이 없거나 true 가 아니면 만들어지지 않는다).
 * 켜면 요청의 error-rate 비율만큼 500 을 돌려준다. /actuator 는 제외해 probe 는 통과시킨다.
 * 인증보다 먼저 실행되게 가장 앞에 둔다. 그래야 로그인 없이 부르는 k6 판단 요청에도 에러가 섞인다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
            // sendError 는 /error 로 에러 디스패치되고, 보안 필터가 그 요청에도 다시 걸려 로그인 없는 요청은 401 로 바뀐다.
            // 그러면 k6 가 401(정상)로 보고 롤백하지 않으므로, 상태와 본문을 직접 써서 디스패치를 거치지 않는다.
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"fault injection\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}