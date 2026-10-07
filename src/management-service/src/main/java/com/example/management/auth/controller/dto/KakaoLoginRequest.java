package com.example.management.auth.controller.dto;

/** 프런트가 카카오 인가 서버로부터 받은 authorization code를 그대로 전달한다 */
public record KakaoLoginRequest(String code) {
}
