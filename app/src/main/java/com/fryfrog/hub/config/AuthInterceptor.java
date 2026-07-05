package com.fryfrog.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthManager authManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!authManager.isEnabled()) {
            return true;
        }

        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/status")
                || path.startsWith("/api-docs") || path.startsWith("/swagger-ui")) {
            return true;
        }

        // 放行图片/媒体资源端点（<img> 标签无法携带 Authorization 头）
        if (isStaticResource(path)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (authManager.validateToken(token)) {
                return true;
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("success", false, "message", "Unauthorized")));
        return false;
    }

    private boolean isStaticResource(String path) {
        return path.matches(".*/cover")
                || path.matches(".*/fanart")
                || path.matches(".*/pages/\\d+")
                || path.matches(".*/artist/image")
                || path.matches(".*/character/.*/image")
                || path.matches(".*/actor/.*/image")
                || path.matches(".*/image")
                || path.matches(".*/stream");
    }
}
