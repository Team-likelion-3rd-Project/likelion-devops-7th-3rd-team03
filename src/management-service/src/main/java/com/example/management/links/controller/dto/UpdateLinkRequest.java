package com.example.management.links.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** PATCH 요청. null 또는 미전달 필드는 기존 값을 유지하며, 제목 삭제는 지원하지 않는다. */
public record UpdateLinkRequest(
        String originalUrl,
        @Size(max = 100) String title,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime expiresAt
) {

    @AssertTrue(message = "수정할 필드가 필요합니다.")
    public boolean isUpdateRequested() {
        return originalUrl != null || title != null || expiresAt != null;
    }

    @AssertTrue(message = "originalUrl은 http 또는 https URL이어야 합니다.")
    public boolean isOriginalUrlValid() {
        return originalUrl == null || LinkUrlValidator.isValid(originalUrl);
    }

    @AssertTrue(message = "expiresAt은 UTC여야 합니다.")
    public boolean isExpirationUtc() {
        return expiresAt == null || ZoneOffset.UTC.equals(expiresAt.getOffset());
    }
}
