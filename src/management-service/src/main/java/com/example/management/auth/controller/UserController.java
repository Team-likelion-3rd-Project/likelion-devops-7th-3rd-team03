package com.example.management.auth.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.auth.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final WithdrawalService withdrawalService;

    /** 회원 탈퇴. 성공하면 FE는 저장한 토큰을 지우고 로그인 화면으로 보낸다 */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@CurrentUser Long userId) {
        withdrawalService.withdraw(userId);
        return ResponseEntity.noContent().build();
    }
}