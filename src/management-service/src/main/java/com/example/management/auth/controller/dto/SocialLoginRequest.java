package com.example.management.auth.controller.dto;

/** 프런트가 소셜 인가 서버로부터 받은 authorization code(및 네이버의 경우 state)를 전달한다 */
public record SocialLoginRequest(String code, String state) {
}