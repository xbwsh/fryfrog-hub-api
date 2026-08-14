package com.fryfrog.hub.config;

import com.fryfrog.hub.common.repository.AuthTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 定时清理已过期的登录令牌，避免 auth_tokens 表无限增长。
 */
@Component
@Slf4j
public class TokenCleanupScheduler {

    private final AuthTokenRepository authTokenRepository;

    public TokenCleanupScheduler(AuthTokenRepository authTokenRepository) {
        this.authTokenRepository = authTokenRepository;
    }

    @Scheduled(cron = "0 20 4 * * *")
    public void cleanupExpiredTokens() {
        long deleted = authTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[TokenCleanup] Removed {} expired tokens", deleted);
        }
    }
}