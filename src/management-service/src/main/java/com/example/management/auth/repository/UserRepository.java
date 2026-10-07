package com.example.management.auth.repository;

import com.example.management.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByKakaoId(Long kakaoId);

    Optional<User> findByUserId(String userId);
}
