package com.example.management.auth.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/** 소셜 API 타임아웃은 인가코드 오류(400)와 구분해 504 로 응답한다. */
class AuthExceptionHandlerTimeoutTest {

    @Test
    void 소셜_API_타임아웃은_504다() {
        var response = new AuthExceptionHandler()
                .handleSocialApiTimeout(new ResourceAccessException("Read timed out"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }
}