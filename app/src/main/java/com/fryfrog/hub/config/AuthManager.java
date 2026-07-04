package com.fryfrog.hub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthManager {

    @Value("${hub.auth.enabled:false}")
    private boolean enabled;

    @Value("${hub.auth.password:}")
    private String password;

    // token → 过期时间戳
    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    private static final long TOKEN_TTL = 7 * 24 * 3600 * 1000L; // 7 天

    public boolean isEnabled() {
        return enabled && password != null && !password.isEmpty();
    }

    public String login(String inputPassword) {
        if (!isEnabled()) return "";
        if (!password.equals(inputPassword)) return null;

        String token = UUID.randomUUID().toString();
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL);
        return token;
    }

    public boolean validateToken(String token) {
        if (!isEnabled()) return true;
        if (token == null || token.isEmpty()) return false;

        Long expiry = tokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }
}
