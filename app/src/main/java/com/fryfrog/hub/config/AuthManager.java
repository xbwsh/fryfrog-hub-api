package com.fryfrog.hub.config;

import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthManager {

    private final UserService userService;

    @Value("${auth.enabled:false}")
    private boolean enabled;

    @Value("${auth.token-ttl-seconds:604800}")
    private long tokenTtlSeconds;

    @Value("${auth.login-max-failures:5}")
    private int maxFailures;

    @Value("${auth.login-lock-minutes:15}")
    private long lockMinutes;

    // token → 持有者信息
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    // username → 登录失败计数/锁定信息
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    public AuthManager(UserService userService) {
        this.userService = userService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LoginResult login(String username, String password, String ip) {
        if (!isEnabled()) {
            return new LoginResult("", null, 0);
        }
        if (username == null || username.isBlank()) {
            return LoginResult.fail("INVALID");
        }

        LoginAttempt attempt = attempts.computeIfAbsent(username, k -> new LoginAttempt());
        synchronized (attempt) {
            if (attempt.lockUntil > System.currentTimeMillis()) {
                long retryAfter = (attempt.lockUntil - System.currentTimeMillis() + 999) / 1000;
                return new LoginResult(null, "LOCKED", retryAfter);
            }
        }

        User user = userService.findByUsername(username).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !userService.verifyPassword(user, password)) {
            recordFailure(attempt);
            return LoginResult.fail("INVALID");
        }

        synchronized (attempt) {
            attempt.failures = 0;
            attempt.lockUntil = 0;
        }
        attempts.remove(username);

        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenEntry(user.getId(), System.currentTimeMillis() + tokenTtlSeconds * 1000));
        userService.updateLastLogin(user.getId(), ip);
        return new LoginResult(token, null, 0);
    }

    /**
     * 校验 token 并返回所属用户 ID；无效/过期返回 null。
     */
    public Long getUserId(String token) {
        if (!isEnabled()) return null;
        if (token == null || token.isEmpty()) return null;

        TokenEntry entry = tokens.get(token);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiresAt) {
            tokens.remove(token);
            return null;
        }
        return entry.userId;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    public void invalidateUserTokens(Long userId) {
        if (userId == null) return;
        tokens.entrySet().removeIf(entry -> entry.getValue().userId == userId);
    }

    private void recordFailure(LoginAttempt attempt) {
        synchronized (attempt) {
            attempt.failures++;
            if (attempt.failures >= maxFailures) {
                attempt.lockUntil = System.currentTimeMillis() + lockMinutes * 60_000;
                attempt.failures = 0;
            }
        }
    }

    public record LoginResult(String token, String error, long retryAfterSeconds) {

        public boolean ok() {
            return token != null;
        }

        static LoginResult fail(String error) {
            return new LoginResult(null, error, 0);
        }
    }

    private record TokenEntry(long userId, long expiresAt) {
    }

    private static class LoginAttempt {
        volatile int failures;
        volatile long lockUntil;
    }
}