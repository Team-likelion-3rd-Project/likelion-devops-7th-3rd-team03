package com.example.management.auth.client;

import com.example.management.auth.config.KakaoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 실제 카카오 서버 대신 MockRestServiceServer로 요청 형태와 응답 매핑을 검증한다 */
class KakaoOAuthClientTest {

    private MockRestServiceServer server;
    private KakaoOAuthClient kakaoOAuthClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        kakaoOAuthClient = new KakaoOAuthClient(builder, new KakaoProperties("client-id", null, "http://localhost/callback"));
    }

    @Test
    @DisplayName("authenticate: 사용자 정보는 secure_resource=true로 요청해 https 이미지 URL을 받는다")
    void authenticate_requestsSecureResourceAndMapsProfile() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me?secure_resource=true"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-access"))
                .andRespond(withSuccess("""
                        {"id":12345,
                         "kakao_account":{"email":"user@test.com",
                           "profile":{"nickname":"닉네임","profile_image_url":"https://k.kakaocdn.net/img.jpg"}}}
                        """, MediaType.APPLICATION_JSON));

        SocialProfile profile = kakaoOAuthClient.authenticate("auth-code", null);

        assertThat(profile).isEqualTo(new SocialProfile("12345", "닉네임", "https://k.kakaocdn.net/img.jpg", "user@test.com"));
        server.verify();
    }
}