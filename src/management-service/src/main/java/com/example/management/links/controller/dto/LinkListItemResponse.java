package com.example.management.links.controller.dto;

import java.time.OffsetDateTime;

/** createdBy는 만든 사람이다. 회원 정보를 찾지 못하거나 탈퇴했으면 null이다. */
public record LinkListItemResponse(
        String linkId,
        String title,
        String shortUrl,
        String originalUrl,
        LinkCreatorResponse createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
}
