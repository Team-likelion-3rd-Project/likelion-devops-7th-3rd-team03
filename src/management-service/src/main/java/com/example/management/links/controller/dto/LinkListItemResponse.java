package com.example.management.links.controller.dto;

import java.time.OffsetDateTime;

public record LinkListItemResponse(
        String linkId,
        String title,
        String shortUrl,
        String originalUrl,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
}
