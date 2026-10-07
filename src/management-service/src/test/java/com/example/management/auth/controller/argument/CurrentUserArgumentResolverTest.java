package com.example.management.auth.controller.argument;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.auth.argument.CurrentUserArgumentResolver;
import com.example.management.auth.domain.User;
import com.example.management.auth.repository.UserRepository;
import com.example.management.common.exception.AuthenticationRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentUserArgumentResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver(userRepository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveArgument_returnsUserMatchedToJwtSubject() throws Exception {
        User user = User.builder().kakaoId(1L).build();
        ReflectionTestUtils.setField(user, "id", 10L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("external-user-id", null));
        when(userRepository.findByUserId("external-user-id")).thenReturn(Optional.of(user));

        Object resolved = resolver.resolveArgument(currentUserParameter(), null, null, null);

        assertThat(resolved).isEqualTo(10L);
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).findByUserId(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo("external-user-id");
    }

    @Test
    void resolveArgument_throwsWhenJwtSubjectHasNoUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("deleted-user-id", null));
        when(userRepository.findByUserId("deleted-user-id")).thenReturn(Optional.empty());

        assertThrows(AuthenticationRequiredException.class,
                () -> resolver.resolveArgument(currentUserParameter(), null, null, null));
    }

    private MethodParameter currentUserParameter() throws NoSuchMethodException {
        Method method = ResolverTarget.class.getDeclaredMethod("handler", Long.class);
        return new MethodParameter(method, 0);
    }

    private static class ResolverTarget {
        @SuppressWarnings("unused")
        void handler(@CurrentUser Long userId) {
        }
    }
}
