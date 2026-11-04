package com.example.management.auth.service;

import com.example.management.auth.domain.User;
import com.example.management.auth.repository.RefreshTokenRepository;
import com.example.management.auth.repository.UserRepository;
import com.example.management.common.exception.AuthenticationRequiredException;
import com.example.management.workspace.service.WorkspaceWithdrawalHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴. 워크스페이스 정리, refresh token 삭제, 회원 상태 변경을 한 트랜잭션으로 처리한다.
 * 남은 access token은 CurrentUserArgumentResolver가 회원 상태를 보고 거부한다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WorkspaceWithdrawalHandler workspaceWithdrawalHandler;

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthenticationRequiredException::new);

        workspaceWithdrawalHandler.handleWithdrawal(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());
        user.withdraw();
    }
}