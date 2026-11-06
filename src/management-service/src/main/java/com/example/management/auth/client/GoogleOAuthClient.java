package com.example.management.auth.client;

import com.example.management.auth.client.dto.GoogleTokenResponse;
import com.example.management.auth.client.dto.GoogleUserInfoResponse;
import com.example.management.auth.config.GoogleProperties;
import com.example.management.auth.domain.SocialProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 구글 로그인 REST API 연동. 인가코드를 토큰으로 교환하고, 토큰으로 사용자 정보를 조회한다.
 * google.client-id가 비어 있으면(비활성 provider) 빈 자체를 등록하지 않는다 — 그래야
 * SocialOAuthClientRegistry가 "등록된 클라이언트 없음"으로 보고 400을 깨끗하게 응답한다.
 * @ConditionalOnProperty는 빈 문자열("")도 "설정됨"으로 보고 통과시켜서 쓸 수 없다.
 */
@Component
@ConditionalOnExpression("!'${google.client-id:}'.isEmpty()")
public class GoogleOAuthClient implements SocialOAuthClient {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";

    private final RestClient restClient;
    private final GoogleProperties googleProperties;

    public GoogleOAuthClient(RestClient.Builder restClientBuilder, GoogleProperties googleProperties) {
        this.restClient = restClientBuilder.build();
        this.googleProperties = googleProperties;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialProfile authenticate(String code, String state) {
        GoogleTokenResponse token = exchangeToken(code);
        GoogleUserInfoResponse user = getUserInfo(token.accessToken());
        return toProfile(user);
    }

    static SocialProfile toProfile(GoogleUserInfoResponse user) {
        return new SocialProfile(user.sub(), user.name(), user.picture(), user.email());
    }

    private GoogleTokenResponse exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", googleProperties.clientId());
        form.add("client_secret", googleProperties.clientSecret());
        form.add("redirect_uri", googleProperties.redirectUri());
        form.add("code", code);

        return restClient.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    private GoogleUserInfoResponse getUserInfo(String accessToken) {
        return restClient.get()
                .uri(USER_INFO_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(GoogleUserInfoResponse.class);
    }
}