package com.example.management.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 가입 대기 세션 설정. session-ttl이 없으면 15분을 쓴다. */
@ConfigurationProperties(prefix = "signup")
public record SignupProperties(Duration sessionTtl) {

    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(15);

    public SignupProperties {
        if (sessionTtl == null) {
            sessionTtl = DEFAULT_SESSION_TTL;
        }
    }
}