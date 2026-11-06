package com.example.management.auth.repository;

import com.example.management.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    /** 회원 탈퇴 시 모든 기기의 refresh token을 지운다 */
    void deleteByUserId(Long userId);
}
