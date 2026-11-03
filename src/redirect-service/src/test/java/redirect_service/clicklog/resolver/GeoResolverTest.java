package redirect_service.clicklog.resolver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoResolverTest {

    @Test
    void 국가는_CloudFront_헤더를_먼저_쓴다() {
        assertThat(GeoResolver.country("KR", "en-US")).isEqualTo("KR");
    }

    @Test
    void 헤더가_없으면_언어_태그의_국가로_대체한다() {
        assertThat(GeoResolver.country(null, "ko-KR")).isEqualTo("KR");
        assertThat(GeoResolver.country("", "ja-jp")).isEqualTo("JP");
    }

    @Test
    void 국가를_알_수_없으면_null() {
        assertThat(GeoResolver.country(null, "ko")).isNull();
        assertThat(GeoResolver.country(null, null)).isNull();
    }

    @Test
    void 형식이_틀린_헤더는_버리고_언어로_대체한다() {
        assertThat(GeoResolver.country("Korea", "ko-KR")).isEqualTo("KR");
        assertThat(GeoResolver.country("<script>", null)).isNull();
    }

    @Test
    void 지역은_CloudFront_헤더_형식이_맞을_때만() {
        assertThat(GeoResolver.region("11")).isEqualTo("11");
        assertThat(GeoResolver.region("ca")).isEqualTo("CA");
        assertThat(GeoResolver.region(null)).isNull();
        assertThat(GeoResolver.region("Seoul-City")).isNull();
    }
}