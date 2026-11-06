package com.example.management.auth.client;

import com.example.management.auth.client.dto.KakaoTokenResponse;
import com.example.management.auth.client.dto.KakaoUserResponse;
import com.example.management.auth.config.KakaoProperties;
import com.example.management.auth.domain.SocialProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** 카카오 로그인 REST API 연동. 인가코드를 토큰으로 교환하고, 토큰으로 사용자 정보를 조회한다. */
@Component
public class KakaoOAuthClient implements SocialOAuthClient {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    // secure_resource=true: 이미지 URL을 https로 받는다(기본값 false면 http로 내려와 프로필 이미지 규칙에서 걸러진다)
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me?secure_resource=true";

    private final RestClient restClient;
    private final KakaoProperties kakaoProperties;

    public KakaoOAuthClient(RestClient.Builder restClientBuilder, KakaoProperties kakaoProperties) {
        this.restClient = restClientBuilder.build();
        this.kakaoProperties = kakaoProperties;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public SocialProfile authenticate(String code, String state) {
        KakaoTokenResponse token = exchangeToken(code);
        KakaoUserResponse user = getUserInfo(token.accessToken());
        return new SocialProfile(String.valueOf(user.id()), user.nickname(), user.profileImageUrl(), user.email());
    }

    public KakaoTokenResponse exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakaoProperties.clientId());
        form.add("redirect_uri", kakaoProperties.redirectUri());
        form.add("code", code);
        if (StringUtils.hasText(kakaoProperties.clientSecret())) {
            form.add("client_secret", kakaoProperties.clientSecret());
        }

        return restClient.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);
    }

    public KakaoUserResponse getUserInfo(String kakaoAccessToken) {
        return restClient.get()
                .uri(USER_INFO_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                .retrieve()
                .body(KakaoUserResponse.class);
    }
}
