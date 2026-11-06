package com.example.management.links.controller.dto;

/** 링크를 만든 사람. userId는 외부 노출용 UUID다. 소유자가 아니라 기록용이다. */
public record LinkCreatorResponse(
        String userId,
        String nickname,
        String profileImageUrl
) {
}
