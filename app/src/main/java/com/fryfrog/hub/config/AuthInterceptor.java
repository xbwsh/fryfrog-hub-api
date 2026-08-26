package com.fryfrog.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthManager authManager;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 普通用户允许的「自身数据」写操作路径（其余写操作仅 ADMIN） */
    private static final Set<String> USER_OWNED_MUTATIONS = Set.of(
            "^/api/v1/auth/logout$",
            "^/api/v1/users/me/password$",
            "^/api/v1/users/me/preferences$",
            "^/api/v1/video/\\d+/favorite$",
            "^/api/v1/video/series/\\d+/favorite$",
            "^/api/v1/video/\\d+/progress$",
            "^/api/v1/video/\\d+/watched$",
            "^/api/v1/music/(songs|albums|artists)/\\d+/star$",
            "^/api/v1/music/(songs|albums|artists)/\\d+/rating$",
            "^/api/v1/music/playlists$",
            "^/api/v1/music/playlists/\\d+$",
            "^/api/v1/music/scrobble$",
            "^/api/v1/music/play-queue$",
            "^/api/v1/music/bookmarks$",
            "^/api/v1/music/bookmarks/\\d+$"
    );

    /** 读操作但属于管理功能，普通用户不可访问 */
    private static final Set<String> ADMIN_ONLY_READS = Set.of(
            "^/api/v1/media-libraries/browse$",
            "^/api/v1/settings.*$",
            "^/api/v1/logs.*$"
    );

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    public AuthInterceptor(AuthManager authManager, UserService userService) {
        this.authManager = authManager;
        this.userService = userService;
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
            // 写入 MDC：访问日志与该请求全部业务日志携带 user=ID
            org.slf4j.MDC.put(com.fryfrog.hub.common.config.TraceIdFilter.USER_ID, String.valueOf(userId));
            if (!userService.isAdmin(userId) && requiresAdmin(request, path)) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            }
            return true;
        }

        return reject(response, HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * 判断是否必须 ADMIN：默认所有状态变更（POST/PUT/PATCH/DELETE）仅 ADMIN，
     * 白名单（用户自身数据）与普通读操作除外；部分读操作也要求 ADMIN。
     */
    private boolean requiresAdmin(HttpServletRequest request, String path) {
        if (MUTATING_METHODS.contains(request.getMethod())) {
            return USER_OWNED_MUTATIONS.stream().noneMatch(path::matches);
        }
        return ADMIN_ONLY_READS.stream().anyMatch(path::matches);
    }

    /** 签名校验的媒体端点：封面/背景/流/季封面/字幕/歌词/头像/转码等 */
    private boolean isSignedMediaPath(String path) {
        return path.matches(".*/cover$")
                || path.matches(".*/fanart$")
                || path.matches(".*/stream$")
                || path.matches(".*/stream/transcode$")
                || path.matches(".*/season/\\d+/cover$")
                || path.matches(".*/subtitles/.*")
                || path.matches(".*/subtitle/vtt$")
                || path.matches(".*/lyrics$")
                || path.matches(".*/image$")
                || path.matches(".*/actor/.*/image$")
                || path.matches(".*/artist/image$")
                || path.matches(".*/character/.*/image$")
                || path.matches(".*/pages/\\d+$");
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
        return reject(response, status, "Unauthorized");
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("success", false, "message", message)));
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
                || path.matches(".*/subtitle/vtt")
                || path.matches(".*/lyrics")
                || path.matches(".*/tmdb-image-proxy");
    }
}