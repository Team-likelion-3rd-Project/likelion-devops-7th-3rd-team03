package com.example.management.auth.service;

import com.example.management.auth.domain.User;
import com.example.management.auth.repository.UserRepository;
import com.example.management.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 저장과 개인 워크스페이스 생성을 한 트랜잭션으로 묶는다. 둘 중 하나라도 실패하면 모두 롤백된다.
 * AuthService.signup()은 유니크 위반 뒤 다시 조회해야 해서 트랜잭션이 없으므로, 묶을 범위만 이 빈으로 분리했다.
 */
@Component
@RequiredArgsConstructor
public class UserRegistrar {

    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;

    @Transactional
    public User register(User newUser) {
        User user = userRepository.saveAndFlush(newUser);
        workspaceService.createPersonal(user.getId());
        return user;
    }
}
