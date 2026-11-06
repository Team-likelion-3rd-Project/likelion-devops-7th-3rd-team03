package com.example.management.auth.client;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.UnsupportedProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialOAuthClientRegistryTest {

    @Test
    @DisplayName("등록된 provider는 해당 클라이언트를, 등록 안 된(비활성) provider는 UnsupportedProviderException을 준다")
    void get_returnsRegisteredClient_andRejectsDisabledProvider() {
        SocialOAuthClient kakao = mock(SocialOAuthClient.class);
        when(kakao.provider()).thenReturn(SocialProvider.KAKAO);
        SocialOAuthClientRegistry registry = new SocialOAuthClientRegistry(List.of(kakao));

        assertThat(registry.get(SocialProvider.KAKAO)).isSameAs(kakao);
        assertThatThrownBy(() -> registry.get(SocialProvider.NAVER))
                .isInstanceOf(UnsupportedProviderException.class);
    }
}
