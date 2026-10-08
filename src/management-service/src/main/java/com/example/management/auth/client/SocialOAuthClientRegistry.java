package com.example.management.auth.client;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.UnsupportedProviderException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 활성화된 SocialOAuthClient 빈들을 provider로 색인한다. 등록 안 된 provider는 비활성으로 간주한다 */
@Component
public class SocialOAuthClientRegistry {

    private final Map<SocialProvider, SocialOAuthClient> clients;

    public SocialOAuthClientRegistry(List<SocialOAuthClient> clients) {
        this.clients = clients.stream()
                .collect(Collectors.toMap(SocialOAuthClient::provider, Function.identity()));
    }

    public SocialOAuthClient get(SocialProvider provider) {
        SocialOAuthClient client = clients.get(provider);
        if (client == null) {
            throw new UnsupportedProviderException(provider.name());
        }
        return client;
    }
}