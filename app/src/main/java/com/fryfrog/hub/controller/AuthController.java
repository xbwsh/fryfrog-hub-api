package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.UserDTO;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.config.AuthInterceptor;
import com.fryfrog.hub.config.AuthManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "多用户登录 + Token 管理")
public class AuthController {

    private final AuthManager authManager;
    private final UserService userService;

    public AuthController(AuthManager authManager, UserService userService) {
        this.authManager = authManager;
        this.userService = userService;
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "用户名+密码登录，返回 token 与用户信息；未传 username 时按 admin 处理（兼容旧单密码登录）")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!authManager.isEnabled()) {
            return ResponseEntity.ok(Map.of("success", true, "token", "", "message", "Auth disabled"));
        }

        String username = body.getOrDefault("username", "admin");
        String password = body.getOrDefault("password", "");
        AuthManager.LoginResult result = authManager.login(username, password, request.getRemoteAddr());

        if (result.ok()) {
            return userService.findByUsername(username)
                    .map(user -> ResponseEntity.ok(Map.of(
                            "success", true,
                            "token", result.token(),
                            "user", UserDTO.from(user))))
                    .orElseGet(() -> ResponseEntity.ok(Map.of("success", true, "token", result.token())));
        }

        if ("LOCKED".equals(result.error())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("success", false, "message", "登录失败次数过多，请稍后再试",
                            "retryAfterSeconds", result.retryAfterSeconds()));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户名或密码错误"));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出", description = "注销当前 token")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authManager.logout(authHeader.substring(7));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/status")
    @Operation(summary = "认证状态", description = "前端判断是否需要登录")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("enabled", authManager.isEnabled()));
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户", description = "返回当前登录用户信息")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return ResponseEntity.ok(Map.of("success", true, "user", UserDTO.from(userService.getUser(userId))));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.USER_ID_ATTR);
        return value instanceof Long id ? id : null;
    }
}