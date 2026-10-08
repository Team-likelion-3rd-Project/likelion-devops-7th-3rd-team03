package com.example.management.auth.controller;

import com.example.management.auth.controller.dto.LoginResponse;
import com.example.management.auth.controller.dto.RefreshTokenRequest;
import com.example.management.auth.controller.dto.SocialLoginRequest;
import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.UnsupportedProviderException;
import com.example.management.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/{provider}/login")
    public ResponseEntity<LoginResponse> login(@PathVariable String provider, @RequestBody SocialLoginRequest request) {
        LoginResponse response = LoginResponse.from(
                authService.login(parseProvider(provider), request.code(), request.state()));
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

    private SocialProvider parseProvider(String provider) {
        try {
            return SocialProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedProviderException(provider);
        }
    }
}
