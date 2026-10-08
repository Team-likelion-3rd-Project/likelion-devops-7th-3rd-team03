package com.example.management.auth.client;

/** provider별 0Auth 클라이언트가 공통으로 반환하는 정규화된 프로필 **/
public record SocialProfile(String socialId, String nickname, String profileImageUrl, String email) {
}