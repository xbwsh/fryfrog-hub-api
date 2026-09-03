package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.repository.UserRepository;
import com.fryfrog.hub.common.util.SubsonicPasswordEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository repository;

    @Mock
    private SubsonicPasswordEncryptor encryptor;

    @InjectMocks
    private UserService service;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void createUser_hashesPasswordAndDefaultsToUserRole() {
        when(repository.existsByUsername("alice")).thenReturn(false);
        when(repository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        User user = service.createUser("alice", "secret123", "爱丽丝", null);

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getRole()).isEqualTo(User.Role.USER);
        assertThat(user.getEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(encoder.matches("secret123", user.getPasswordHash())).isTrue();
        verify(repository).save(any(User.class));
    }

    @Test
    void createUser_rejectsDuplicateUsername() {
        when(repository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser("alice", "secret123", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createUser_rejectsShortPassword() {
        assertThatThrownBy(() -> service.createUser("alice", "123", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("至少");
    }

    @Test
    void createUser_rejectsInvalidUsername() {
        assertThatThrownBy(() -> service.createUser("a b", "secret123", null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void verifyPassword_matchesHashedPassword() {
        User user = new User();
        user.setUsername("bob");
        user.setPasswordHash(encoder.encode("correct-horse"));

        assertThat(service.verifyPassword(user, "correct-horse")).isTrue();
        assertThat(service.verifyPassword(user, "wrong")).isFalse();
        assertThat(service.verifyPassword(user, null)).isFalse();
    }

    @Test
    void changePassword_wrongOldPasswordRejected() {
        User user = new User();
        user.setId(1L);
        user.setUsername("bob");
        user.setPasswordHash(encoder.encode("old-pass-123"));
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(1L, "wrong-old", "new-pass-456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("原密码");
    }

    @Test
    void changePassword_updatesHash() {
        User user = new User();
        user.setId(1L);
        user.setUsername("bob");
        user.setPasswordHash(encoder.encode("old-pass-123"));
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.changePassword(1L, "old-pass-123", "new-pass-456");

        assertThat(encoder.matches("new-pass-456", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-pass-123", user.getPasswordHash())).isFalse();
    }

    @Test
    void isAdmin_returnsRoleBasedResult() {
        User admin = new User();
        admin.setRole(User.Role.ADMIN);
        when(repository.findById(1L)).thenReturn(Optional.of(admin));

        assertThat(service.isAdmin(1L)).isTrue();
        assertThat(service.isAdmin(999L)).isFalse();
        assertThat(service.isAdmin(null)).isFalse();
    }
}