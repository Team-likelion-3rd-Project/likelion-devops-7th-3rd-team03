package com.example.management.links.controller.dto;

import java.time.OffsetDateTime;

/** 생성·수정·목록 조회에서 공통으로 사용하는 링크 응답 계약. */
public record LinkResponse(
        String linkId,
        String title,
        String shortUrl,
        String originalUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        LinkStatus status
) {
}
