package com.example.management.auth.client;

import com.example.management.auth.client.dto.NaverTokenResponse;
import com.example.management.auth.client.dto.NaverUserResponse;
import com.example.management.auth.config.NaverProperties;
import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.MissingOAuthStateException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 네이버 로그인 REST API 연동. 인가코드와 state로 토큰을 교환하고, 토큰으로 사용자 정보를 조회한다.
 * naver.client-id가 비어 있으면(비활성 provider) 빈 자체를 등록하지 않는다 — GoogleOAuthClient와 같은 이유.
 * 네이버는 실패도 HTTP 200으로 응답하므로 RestClient가 예외를 던지지 않는다. 그래서 응답 내용을 보고
 * 직접 RestClientException을 던져, 다른 provider의 API 실패와 같은 400 응답으로 처리되게 한다.
 */
@Component
@ConditionalOnExpression("!'${naver.client-id:}'.isEmpty()")
public class NaverOAuthClient implements SocialOAuthClient {

    private static final String TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    private final RestClient restClient;
    private final NaverProperties naverProperties;

    public NaverOAuthClient(RestClient.Builder restClientBuilder, NaverProperties naverProperties) {
        this.restClient = restClientBuilder.build();
        this.naverProperties = naverProperties;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.NAVER;
    }

    @Override
    public SocialProfile authenticate(String code, String state) {
        if (!StringUtils.hasText(state)) {
            throw new MissingOAuthStateException(provider().name());
        }
        NaverTokenResponse token = exchangeToken(code, state);
        NaverUserResponse user = getUserInfo(token.accessToken());
        return toProfile(user);
    }

    /** 닉네임을 이름으로 가입 폼을 미리 채운다 */
    static SocialProfile toProfile(NaverUserResponse user) {
        String nickname = StringUtils.hasText(user.nickname()) ? user.nickname() : user.name();
        return new SocialProfile(user.id(), nickname, user.profileImage(), user.email());
    }

    private NaverTokenResponse exchangeToken(String code, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", naverProperties.clientId());
        form.add("client_secret", naverProperties.clientSecret());
        form.add("code", code);
        form.add("state", state);

        NaverTokenResponse token = restClient.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(NaverTokenResponse.class);
        if (token == null || !StringUtils.hasText(token.accessToken())) {
            String error = token == null ? "빈 응답" : token.error() + ": " + token.errorDescription();
            throw new RestClientException("네이버 토큰 발급 실패 (" + error + ")");
        }
        return token;
    }

    private NaverUserResponse getUserInfo(String accessToken) {
        NaverUserResponse user = restClient.get()
                .uri(USER_INFO_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NaverUserResponse.class);
        if (user == null || !user.isSuccess()) {
            String error = user == null ? "빈 응답" : user.resultcode() + ": " + user.message();
            throw new RestClientException("네이버 사용자 정보 조회 실패 (" + error + ")");
        }
        return user;
    }
}