package com.example.management.links.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** POST /api/v1/links 요청 계약. title은 links.title에 대응하는 표시용 이름이다. */
public record CreateLinkRequest(
        @NotBlank String originalUrl,
        @NotNull
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime expiresAt,
        @Size(max = 100) String title
) {

    @AssertTrue(message = "originalUrl은 http 또는 https URL이어야 합니다.")
    public boolean isOriginalUrlValid() {
        return LinkUrlValidator.isValid(originalUrl);
    }

    @AssertTrue(message = "expiresAt은 UTC여야 합니다.")
    public boolean isExpirationUtc() {
        return expiresAt != null && ZoneOffset.UTC.equals(expiresAt.getOffset());
    }
}
