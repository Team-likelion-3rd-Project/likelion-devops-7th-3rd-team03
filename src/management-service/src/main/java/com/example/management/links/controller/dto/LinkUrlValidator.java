package com.example.management.links.controller.dto;

import java.net.URI;

/** 링크 생성·수정 요청에서 공통으로 사용하는 최소 URL 검증 규칙이다. */
public final class LinkUrlValidator {

    private static final int MAX_URL_LENGTH = 2048;

    private LinkUrlValidator() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
            return false;
        }

        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
