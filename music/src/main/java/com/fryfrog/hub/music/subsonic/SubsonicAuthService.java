package com.fryfrog.hub.music.subsonic;

import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.repository.UserRepository;
import com.fryfrog.hub.common.util.SubsonicPasswordEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.fryfrog.hub.music.subsonic.SubsonicApiException.ERROR_AUTH;

/**
 * Subsonic 协议认证：与 Navidrome 一致，支持
 * <ul>
 *   <li>{@code p}：明文或 {@code enc:} 十六进制密码（优先比对 subsonicPassword 副本；
 *       存量用户无副本时回退 BCrypt 校验，此时仅支持 p 认证）</li>
 *   <li>{@code t} + {@code s}：token = md5(明文密码 + salt)（BCrypt 不可反推，故维护明文副本）</li>
 * </ul>
 */
@Service
@Slf4j
public class SubsonicAuthService {

    private final boolean authEnabled;
    private final UserRepository userRepository;
    private final SubsonicPasswordEncryptor encryptor;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public SubsonicAuthService(@Value("${auth.enabled:true}") boolean authEnabled,
                               UserRepository userRepository,
                               SubsonicPasswordEncryptor encryptor) {
        this.authEnabled = authEnabled;
        this.userRepository = userRepository;
        this.encryptor = encryptor;
    }

    /** 认证通过返回用户；系统认证关闭（AUTH_ENABLED=false）时返回 null（调用方按匿名处理）。 */
    public User authenticate(String username, String password, String token, String salt) {
        if (!authEnabled) {
            return null;
        }
        if (username == null || username.isBlank()) {
            throw new SubsonicApiException(ERROR_AUTH, "Missing username");
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new SubsonicApiException(ERROR_AUTH, "Wrong username or password");
        }
        String plain = encryptor.decrypt(user.getSubsonicPassword());

        boolean valid = false;
        if (password != null && !password.isBlank()) {
            String pass = password;
            if (pass.startsWith("enc:")) {
                try {
                    pass = new String(HexFormat.of().parseHex(pass.substring(4)), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new SubsonicApiException(ERROR_AUTH, "Invalid enc password");
                }
            }
            if (plain != null) {
                valid = pass.equals(plain);
            } else if (user.getPasswordHash() != null) {
                // 存量用户无明文副本：回退 BCrypt 校验
                valid = encoder.matches(pass, user.getPasswordHash());
            }
        } else if (token != null && !token.isBlank() && salt != null && !salt.isBlank()) {
            if (plain == null) {
                throw new SubsonicApiException(ERROR_AUTH, "Token auth requires password reset (no subsonic password on record)");
            }
            valid = md5Hex(plain + salt).equalsIgnoreCase(token);
        }

        if (!valid) {
            throw new SubsonicApiException(ERROR_AUTH, "Wrong username or password");
        }
        return user;
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}