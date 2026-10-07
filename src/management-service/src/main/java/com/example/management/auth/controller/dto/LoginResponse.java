package com.example.management.auth.controller.dto;

import com.example.management.auth.service.LoginResult;

public record LoginResponse(String accessToken, String refreshToken, boolean isNewUser) {

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(result.accessToken(), result.refreshToken(), result.isNewUser());
    }
}
