package com.fryfrog.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
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

        // 放行图片/媒体资源端点（<img>/<video> 无法携带 Authorization 头）
        if (isStaticResource(path)) {
            // 核心媒体资源要求签名 URL，防止 URL 泄露即可访问
            if (isSignedMediaPath(path)) {
                return validateMediaSignature(request, response);
            }
            // 其余媒体端点（海报占位图、演员头像、转码流等）维持放行
            return true;
        }

        Long userId = authManager.getUserId(extractToken(request));
        if (userId != null) {
            request.setAttribute(UserContext.USER_ID_ATTR, userId);
            return true;
        }

        return reject(response, HttpServletResponse.SC_UNAUTHORIZED);
    }

    /** 签名校验的媒体端点：封面/背景/流/季封面/字幕 */
    private boolean isSignedMediaPath(String path) {
        return path.matches(".*/cover$")
                || path.matches(".*/fanart$")
                || path.matches(".*/stream$")
                || path.matches(".*/season/\\d+/cover$")
                || path.matches(".*/subtitles/.*");
    }

    private boolean validateMediaSignature(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String sig = request.getParameter("sig");
        long exp;
        try {
            exp = Long.parseLong(request.getParameter("exp"));
        } catch (NumberFormatException | NullPointerException e) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED);
        }
        if (MediaUrlSigner.verify(request.getRequestURI(), exp, sig)) {
            return true;
        }
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("success", false, "message", "Unauthorized")));
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isStaticResource(String path) {
        return path.matches(".*/cover")
                || path.matches(".*/fanart")
                || path.matches(".*/pages/\\d+")
                || path.matches(".*/artist/image")
                || path.matches(".*/character/.*/image")
                || path.matches(".*/actor/.*/image")
                || path.matches(".*/image")
                || path.matches(".*/stream")
                || path.matches(".*/stream/transcode")
                || path.matches(".*/subtitles/.*")
                || path.matches(".*/subtitle/vtt");
    }
}