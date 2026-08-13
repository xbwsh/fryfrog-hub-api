package com.fryfrog.hub.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录用户上下文。AuthInterceptor 将已认证用户 ID 写入请求属性，
 * 业务层通过本工具读取。认证关闭（AUTH_ENABLED=false）时返回匿名 ID，
 * 使收藏/进度等数据在单用户模式下全局共享。
 */
public final class UserContext {

    /** 请求属性名：当前已认证用户 ID（Long） */
    public static final String USER_ID_ATTR = "auth.userId";

    /** 认证关闭时的匿名共享档案 ID（非真实用户，仅用于数据归属键） */
    public static final long ANONYMOUS_ID = -1L;

    private UserContext() {
    }

    /** 返回当前请求用户 ID；认证关闭/未登录时返回 {@link #ANONYMOUS_ID}。 */
    public static long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ID_ATTR);
        if (value instanceof Long id) {
            return id;
        }
        return ANONYMOUS_ID;
    }

    /** 从请求上下文读取当前用户 ID；无 Web 请求（如后台扫描任务）时返回 null。 */
    public static Long currentUserIdOrNull() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            Object value = request.getAttribute(USER_ID_ATTR);
            if (value instanceof Long id) {
                return id;
            }
        }
        return null;
    }
}