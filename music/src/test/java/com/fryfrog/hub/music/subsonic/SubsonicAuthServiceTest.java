package com.fryfrog.hub.music.subsonic;

import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.repository.UserRepository;
import com.fryfrog.hub.common.util.SubsonicPasswordEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubsonicAuthServiceTest {

    private static final String PASSWORD = "secret123";

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubsonicPasswordEncryptor encryptor;

    private SubsonicAuthService authService;

    @BeforeEach
    void setUp() {
        when(encryptor.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        authService = new SubsonicAuthService(true, userRepository, encryptor);
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setEnabled(true);
        user.setSubsonicPassword(PASSWORD);
        return user;
    }

    @Test
    void plainPasswordAuthenticates() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user()));
        User user = authService.authenticate("admin", PASSWORD, null, null);
        assertThat(user.getUsername()).isEqualTo("admin");
    }

    @Test
    void hexEncodedPasswordAuthenticates() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user()));
        String hex = HexFormat.of().formatHex(PASSWORD.getBytes(StandardCharsets.UTF_8));
        User user = authService.authenticate("admin", "enc:" + hex, null, null);
        assertThat(user).isNotNull();
    }

    @Test
    void tokenAuthAuthenticates() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user()));
        String salt = "c19b2d";
        String token = md5Hex(PASSWORD + salt);
        User user = authService.authenticate("admin", null, token, salt);
        assertThat(user).isNotNull();
    }

    @Test
    void wrongPasswordRejected() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user()));
        assertThatThrownBy(() -> authService.authenticate("admin", "wrong", null, null))
                .isInstanceOf(SubsonicApiException.class)
                .satisfies(e -> assertThat(((SubsonicApiException) e).getCode()).isEqualTo(SubsonicApiException.ERROR_AUTH));
    }

    @Test
    void unknownUserRejected() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.authenticate("nobody", PASSWORD, null, null))
                .isInstanceOf(SubsonicApiException.class);
    }

    @Test
    void disabledAuthReturnsAnonymous() {
        SubsonicAuthService anonymousAuth = new SubsonicAuthService(false, userRepository, encryptor);
        assertThat(anonymousAuth.authenticate(null, null, null, null)).isNull();
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}