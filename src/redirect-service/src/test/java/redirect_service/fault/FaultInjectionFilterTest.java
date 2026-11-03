package redirect_service.fault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애 주입 필터는 설정값이 정확히 true 일 때만 켜지고, 켜지면 /actuator 를 뺀 요청을 실패시키는지 확인한다.
 * 앱 전체(DB·Redis)를 띄우지 않고 이 클래스만 올려서 본다.
 */
class FaultInjectionFilterTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FaultInjectionFilter.class);

    @Test
    void 설정이_없으면_Bean이_만들어지지_않는다() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FaultInjectionFilter.class));
    }

    @Test
    void 빈_값이나_false면_Bean이_만들어지지_않는다() {
        runner.withPropertyValues("app.fault-injection.enabled=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FaultInjectionFilter.class));
        runner.withPropertyValues("app.fault-injection.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FaultInjectionFilter.class));
    }

    @Test
    void true면_Bean이_만들어진다() {
        runner.withPropertyValues("app.fault-injection.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(FaultInjectionFilter.class));
    }

    @Test
    void 비율이_1이면_요청은_500이고_actuator는_통과한다() throws Exception {
        FaultInjectionFilter filter = new FaultInjectionFilter(1.0);

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/abc123"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(500);

        MockHttpServletResponse probe = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"), probe, new MockFilterChain());
        assertThat(probe.getStatus()).isEqualTo(200);
    }

    @Test
    void 비율이_0이면_모든_요청이_통과한다() throws Exception {
        FaultInjectionFilter filter = new FaultInjectionFilter(0.0);

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/abc123"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);
    }
}