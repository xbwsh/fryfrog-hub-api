package com.fryfrog.hub.config;

import com.fryfrog.hub.common.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public class UserInitializer {

    private static final Set<String> WEAK_PASSWORDS = Set.of("1234", "123456", "12345678", "admin", "password");

    private final UserService userService;
    private final Environment environment;

    public UserInitializer(UserService userService, Environment environment) {
        this.userService = userService;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        if (userService.hasUsers()) {
            return;
        }

        String configured = environment.getProperty("auth.password", "");
        boolean generateRandom = configured == null || configured.isBlank();

        if (generateRandom) {
            String randomPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@Aa1";
            userService.createInitialAdmin(randomPassword);
            log.warn("未配置 AUTH_PASSWORD，已创建初始管理员账号 admin，随机密码: {}", randomPassword);
            log.warn("请立即登录并修改密码");
        } else {
            if (configured.length() < 8 || WEAK_PASSWORDS.contains(configured)) {
                log.warn("AUTH_PASSWORD 强度过弱，建议登录后立即修改 admin 密码");
            }
            userService.createInitialAdmin(configured);
            log.info("已创建初始管理员账号 admin（密码来自 AUTH_PASSWORD 配置）");
        }
    }
}