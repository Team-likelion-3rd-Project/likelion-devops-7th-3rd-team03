package com.example.management.auth.repository;

import com.example.management.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndSocialId(User.SocialProvider provider, String socialId);

    Optional<User> findByUserId(String userId);
}
