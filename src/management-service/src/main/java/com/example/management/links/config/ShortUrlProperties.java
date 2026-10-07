package com.example.management.links.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 단축 URL의 base 주소.
 *
 * 환경 변수 SHORT_URL_BASE_URL로 주입한다. slug만 DB에 저장하고, 이 값은 응답을 만들 때만 사용한다.
 */
@ConfigurationProperties(prefix = "short-url")
public record ShortUrlProperties(String baseUrl) {
}
