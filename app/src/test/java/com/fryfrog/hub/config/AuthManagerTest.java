package com.fryfrog.hub.config;

import com.fryfrog.hub.common.model.AuthToken;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.repository.AuthTokenRepository;
import com.fryfrog.hub.common.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthManagerTest {

    @Mock
    private UserService userService;

    @Mock
    private AuthTokenRepository authTokenRepository;

    private AuthManager authManager;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // 内存模拟 auth_tokens 存储
    private final ConcurrentHashMap<String, AuthToken> tokenStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        authManager = new AuthManager(userService, authTokenRepository);
        ReflectionTestUtils.setField(authManager, "enabled", true);
        ReflectionTestUtils.setField(authManager, "tokenTtlSeconds", 3600L);
        ReflectionTestUtils.setField(authManager, "maxFailures", 2);
        ReflectionTestUtils.setField(authManager, "lockMinutes", 15L);

        when(authTokenRepository.save(any(AuthToken.class))).thenAnswer(inv -> {
            AuthToken t = inv.getArgument(0);
            tokenStore.put(t.getToken(), t);
            return t;
        });
        when(authTokenRepository.findByToken(anyString())).thenAnswer(inv ->
                Optional.ofNullable(tokenStore.get(inv.getArgument(0))));
        doAnswer(inv -> {
            tokenStore.remove(inv.getArgument(0));
            return null;
        }).when(authTokenRepository).deleteByToken(anyString());
    }

    private User adminUser(Long id, String rawPassword) {
        User user = new User();
        user.setId(id);
        user.setUsername("admin");
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setEnabled(true);
        user.setRole(User.Role.ADMIN);
        return user;
    }

    @Test
    void login_returnsTokenForValidCredentials() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(adminUser(7L, "secret-pass")));
        when(userService.verifyPassword(any(), anyString())).thenReturn(true);

        AuthManager.LoginResult result = authManager.login("admin", "secret-pass", "127.0.0.1");

        assertThat(result.ok()).isTrue();
        assertThat(result.token()).isNotBlank();
        assertThat(authManager.getUserId(result.token())).isEqualTo(7L);
    }

    @Test
    void login_rejectsWrongPassword() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(adminUser(7L, "secret-pass")));

        AuthManager.LoginResult result = authManager.login("admin", "wrong-pass", "127.0.0.1");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("INVALID");
        assertThat(authManager.getUserId("any-token")).isNull();
    }

    @Test
    void login_rejectsUnknownUser() {
        when(userService.findByUsername("nobody")).thenReturn(Optional.empty());

        AuthManager.LoginResult result = authManager.login("nobody", "whatever", "127.0.0.1");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("INVALID");
    }

    @Test
    void login_locksAfterTooManyFailures() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(adminUser(7L, "secret-pass")));

        authManager.login("admin", "wrong-1", "127.0.0.1");
        authManager.login("admin", "wrong-2", "127.0.0.1");

        AuthManager.LoginResult locked = authManager.login("admin", "secret-pass", "127.0.0.1");

        assertThat(locked.ok()).isFalse();
        assertThat(locked.error()).isEqualTo("LOCKED");
        assertThat(locked.retryAfterSeconds()).isPositive();
    }

    @Test
    void login_whenDisabled_returnsEmptyToken() {
        ReflectionTestUtils.setField(authManager, "enabled", false);

        AuthManager.LoginResult result = authManager.login("admin", "anything", "127.0.0.1");

        assertThat(result.ok()).isTrue();
        assertThat(result.token()).isEmpty();
        assertThat(authManager.getUserId("whatever")).isNull();
    }

    @Test
    void logout_removesToken() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(adminUser(7L, "secret-pass")));
        when(userService.verifyPassword(any(), anyString())).thenReturn(true);

        String token = authManager.login("admin", "secret-pass", "127.0.0.1").token();
        assertThat(authManager.getUserId(token)).isEqualTo(7L);

        authManager.logout(token);

        assertThat(authManager.getUserId(token)).isNull();
    }

    @Test
    void getUserId_nullOrEmptyTokenInvalid() {
        assertThat(authManager.getUserId(null)).isNull();
        assertThat(authManager.getUserId("")).isNull();
    }

    @Test
    void login_updatesLastLoginOnSuccess() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(adminUser(7L, "secret-pass")));
        when(userService.verifyPassword(any(), anyString())).thenReturn(true);

        authManager.login("admin", "secret-pass", "10.0.0.8");

        verify(userService).updateLastLogin(7L, "10.0.0.8");
    }
}