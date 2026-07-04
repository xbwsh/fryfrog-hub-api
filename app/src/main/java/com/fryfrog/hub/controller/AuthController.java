package com.fryfrog.hub.controller;

import com.fryfrog.hub.config.AuthManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "密码登录 + Token 管理")
public class AuthController {

    private final AuthManager authManager;

    public AuthController(AuthManager authManager) {
        this.authManager = authManager;
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "输入密码，返回系统生成的 token")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        if (!authManager.isEnabled()) {
            return ResponseEntity.ok(Map.of("success", true, "token", "", "message", "Auth disabled"));
        }

        String password = body.getOrDefault("password", "");
        String token = authManager.login(password);

        if (token != null) {
            return ResponseEntity.ok(Map.of("success", true, "token", token));
        }
        return ResponseEntity.status(401).body(Map.of("success", false, "message", "Wrong password"));
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
}
