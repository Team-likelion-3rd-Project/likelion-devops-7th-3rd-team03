package com.example.management.auth.controller;

import com.example.management.auth.controller.dto.KakaoLoginRequest;
import com.example.management.auth.controller.dto.LoginResponse;
import com.example.management.auth.controller.dto.RefreshTokenRequest;
import com.example.management.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/kakao/login")
    public ResponseEntity<LoginResponse> kakaoLogin(@RequestBody KakaoLoginRequest request) {
        LoginResponse response = LoginResponse.from(authService.login(request.code()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reissue")
    public ResponseEntity<LoginResponse> reissue(@RequestBody RefreshTokenRequest request) {
        LoginResponse response = LoginResponse.from(authService.reissue(request.refreshToken()));
        return ResponseEntity.ok(response);
    }
}
