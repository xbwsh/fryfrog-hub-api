package com.fryfrog.hub.music.subsonic;

/**
 * Subsonic 协议错误。code 对应 Subsonic 错误码：40 认证失败、70 数据不存在等。
 */
public class SubsonicApiException extends RuntimeException {

    public static final int ERROR_GENERIC = 0;
    public static final int ERROR_MISSING_PARAM = 10;
    public static final int ERROR_PROTOCOL_VERSION = 20;
    public static final int ERROR_SERVER_VERSION = 30;
    public static final int ERROR_AUTH = 40;
    public static final int ERROR_UNAUTHORIZED = 50;
    public static final int ERROR_NOT_FOUND = 70;

    private final int code;

    public SubsonicApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}