package redirect_service.clicklog.resolver;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 클릭의 국가·지역을 정한다 (#207). IP 는 쓰지 않는다.
 *
 * 국가: CloudFront-Viewer-Country (ISO 3166-1 두 글자) → 없으면 Accept-Language 의 국가 서브태그 (ko-KR → KR)
 * 지역: CloudFront-Viewer-Country-Region (ISO 3166-2 하위 코드, 예: 11 서울, 41 경기) → 없으면 null
 *
 * 온프렘에는 CloudFront 가 없어 헤더가 없다. 국가는 언어 태그로 대체하고 지역은 비운다.
 * 헤더 값은 형식이 맞을 때만 쓴다 (CloudFront 를 거치지 않은 요청이 임의 값을 넣는 경우를 거른다).
 */
public final class GeoResolver {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern REGION = Pattern.compile("[A-Z0-9]{1,3}");

    private GeoResolver() {
    }

    public static String country(String viewerCountry, String language) {
        String fromHeader = normalize(viewerCountry);
        if (fromHeader != null && COUNTRY.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        if (language == null) {
            return null;
        }
        String[] parts = language.split("-");
        if (parts.length < 2) {
            return null;
        }
        String subtag = normalize(parts[1]);
        return subtag != null && COUNTRY.matcher(subtag).matches() ? subtag : null;
    }

    public static String region(String viewerRegion) {
        String value = normalize(viewerRegion);
        return value != null && REGION.matcher(value).matches() ? value : null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}