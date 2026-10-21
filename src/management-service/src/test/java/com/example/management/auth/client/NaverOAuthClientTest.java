package com.example.management.auth.client;

import com.example.management.auth.client.dto.NaverUserResponse;
import com.example.management.auth.config.NaverProperties;
import com.example.management.auth.exception.MissingOAuthStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 실제 네이버 서버 대신 MockRestServiceServer로 요청 형태와 응답 매핑을 검증한다 */
class NaverOAuthClientTest {

    private static final String TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    private MockRestServiceServer server;
    private NaverOAuthClient naverOAuthClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        naverOAuthClient = new NaverOAuthClient(builder, new NaverProperties("client-id", "client-secret"));
    }

    @Test
    @DisplayName("authenticate: 토큰 요청에 state를 담고, response 안의 프로필을 매핑한다")
    void authenticate_sendsStateAndMapsProfile() {
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("client_id", "client-id");
        expectedForm.add("client_secret", "client-secret");
        expectedForm.add("code", "auth-code");
        expectedForm.add("state", "state-xyz");

        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("{\"access_token\":\"naver-access\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer naver-access"))
                .andRespond(withSuccess("""
                        {"resultcode":"00","message":"success",
                         "response":{"id":"naver-id-1","name":"홍길동","profile_image":"https://phinf.pstatic.net/a.jpg"}}
                        """, MediaType.APPLICATION_JSON));

        SocialProfile profile = naverOAuthClient.authenticate("auth-code", "state-xyz");

        assertThat(profile).isEqualTo(new SocialProfile("naver-id-1", "홍길동", "https://phinf.pstatic.net/a.jpg", null));
        server.verify();
    }

    @Test
    @DisplayName("authenticate: state가 없거나 비어 있으면 네이버를 호출하지 않고 예외를 던진다")
    void authenticate_withoutState_throwsBeforeCallingNaver() {
        assertThatThrownBy(() -> naverOAuthClient.authenticate("auth-code", null))
                .isInstanceOf(MissingOAuthStateException.class);
        assertThatThrownBy(() -> naverOAuthClient.authenticate("auth-code", " "))
                .isInstanceOf(MissingOAuthStateException.class);
        server.verify();
    }

    @Test
    @DisplayName("authenticate: 토큰 발급 실패가 HTTP 200 + error로 와도 RestClientException으로 바꾼다")
    void authenticate_tokenErrorWith200_throwsRestClientException() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"error\":\"invalid_request\",\"error_description\":\"no valid data in session\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> naverOAuthClient.authenticate("used-code", "state"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("invalid_request");
        server.verify();
    }

    @Test
    @DisplayName("authenticate: 사용자 정보 조회의 resultcode가 00이 아니면 RestClientException을 던진다")
    void authenticate_userInfoFailure_throwsRestClientException() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"naver-access\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI))
                .andRespond(withSuccess("{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> naverOAuthClient.authenticate("auth-code", "state"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("024");
        server.verify();
    }

    @Test
    @DisplayName("toProfile: 닉네임이 있으면 이름보다 닉네임을 쓴다")
    void toProfile_prefersNickname() {
        NaverUserResponse user = new NaverUserResponse("00", "success",
                new NaverUserResponse.Profile("naver-id-2", "별명", "홍길동", "user@naver.com", "https://image"));

        SocialProfile profile = NaverOAuthClient.toProfile(user);

        assertThat(profile).isEqualTo(new SocialProfile("naver-id-2", "별명", "https://image", "user@naver.com"));
    }

    @Test
    @DisplayName("toProfile: 동의 항목이 하나도 없으면 id 외에는 예외 없이 null로 매핑된다")
    void toProfile_handlesMissingFields() {
        NaverUserResponse user = new NaverUserResponse("00", "success",
                new NaverUserResponse.Profile("naver-id-3", null, null, null, null));

        SocialProfile profile = NaverOAuthClient.toProfile(user);

        assertThat(profile).isEqualTo(new SocialProfile("naver-id-3", null, null, null));
    }
}
