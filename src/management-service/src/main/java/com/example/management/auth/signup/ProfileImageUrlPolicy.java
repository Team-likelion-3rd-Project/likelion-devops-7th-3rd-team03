package com.example.management.auth.signup;

/**
 * 프로필 이미지 URL 허용 규칙. https://로 시작하고 500자 이하인 값만 허용한다(javascript:, data:, http: 등 차단).
 * 서버는 URL을 직접 가져오지 않으며, 가입 대기 세션의 prefill(B6)과 가입 요청 검증(B7)이 같은 규칙을 쓴다.
 */
public final class ProfileImageUrlPolicy {

    public static final int MAX_LENGTH = 500;
    private static final String REQUIRED_PREFIX = "https://";

    private ProfileImageUrlPolicy() {
    }

    public static boolean isAllowed(String url) {
        return url != null
                && url.length() <= MAX_LENGTH
                && url.startsWith(REQUIRED_PREFIX);
    }

    /** 허용되지 않는 값은 null로 바꾼다. FE는 null이면 기본 아이콘을 보여준다. */
    public static String allowedOrNull(String url) {
        return isAllowed(url) ? url : null;
    }
}