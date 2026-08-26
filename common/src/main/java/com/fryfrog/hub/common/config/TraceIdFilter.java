package com.fryfrog.hub.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪与访问日志过滤器（日志规范核心组件）。
 *
 * 职责：
 * 1. 为每个请求生成/透传 traceId，写入 MDC，贯穿该请求的全部业务日志；
 *    响应头 X-Trace-Id 返回给客户端，便于用户反馈时定位日志。
 * 2. 输出统一访问日志：method uri status 耗时 userId ip。
 * 3. 慢请求告警：超过 SLOW_THRESHOLD_MS 打 WARN。
 *
 * 规范详见 docs/LOGGING.md。
 */
@Component
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    private static final String HEADER = "X-Trace-Id";

    /** 超过该耗时（ms）的请求打慢请求告警 */
    private static final long SLOW_THRESHOLD_MS = 2_000;

    /** 静态媒体资源与文档端点不记访问日志（图片墙一次几十个请求会刷屏） */
    private static boolean isQuietPath(String uri) {
return uri.startsWith("/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.matches(".*/(cover|fanart|stream|stream/transcode|lyrics|subtitle/vtt|pages/\\d+)$")
                || uri.matches(".*/actor/.*/image$")
                || uri.matches(".*/character/.*/image$")
                || uri.matches(".*/artist/image$")
                || uri.matches(".*/subtitles/.*")
                || uri.matches(".*/tmdb-image-proxy");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 32) {
            traceId = ShortUuid.generate();
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            try {
                String uri = request.getRequestURI();
                if (!isQuietPath(uri)) {
                    String userId = MDC.get(USER_ID);
                    int status = response.getStatus();
                    String line = "{} {} -> {} {}ms{} {}";
                    if (status >= 500) {
                        log.error(line, request.getMethod(), uri, status, cost, suffix(userId), clientIp(request));
                    } else if (cost > SLOW_THRESHOLD_MS) {
                        log.warn(line + " (slow)", request.getMethod(), uri, status, cost, suffix(userId), clientIp(request));
                    } else {
                        log.info(line, request.getMethod(), uri, status, cost, suffix(userId), clientIp(request));
                    }
                }
            } finally {
                MDC.clear();
            }
        }
    }

    private static String suffix(String userId) {
        return userId == null ? "" : " user=" + userId;
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }

    /** 8 位短 ID：足够单机内唯一，日志中更紧凑 */
    private static final class ShortUuid {
        static String generate() {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
    }
}
