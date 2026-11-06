package com.example.management.links.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * redirect-service와 공유하는 Redis 캐시 TTL 정책이다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.redirect-cache")
public class RedirectCacheProperties {

    private Duration ttl = Duration.ofMinutes(10);
}
