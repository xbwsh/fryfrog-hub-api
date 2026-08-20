package com.fryfrog.hub.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 媒体资源（封面/流/字幕等）签名 URL 工具。
 * URL 追加 exp（过期时间戳）与 sig（HMAC-SHA256 签名），
 * 由 AuthInterceptor 校验，防止知晓 URL 即可访问媒体资源。
 */
public final class MediaUrlSigner {

    private static final byte[] SECRET = loadSecret();
    private static final long DEFAULT_TTL_MILLIS = 7 * 24 * 3600 * 1000L;

    private static byte[] loadSecret() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("data/media_secret.key");
            if (java.nio.file.Files.exists(path)) {
                String hex = java.nio.file.Files.readString(path).trim();
                if (!hex.isEmpty()) {
                    return HexFormat.of().parseHex(hex);
                }
            }
            byte[] secret = new SecureRandom().generateSeed(32);
            String hex = HexFormat.of().formatHex(secret);
            try {
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.writeString(path, hex);
            } catch (Exception ignored) {
            }
            return secret;
        } catch (Exception e) {
            return new SecureRandom().generateSeed(32);
        }
    }

    private MediaUrlSigner() {
    }

    /** 对相对 URL 签名，追加 exp 与 sig 参数（默认 7 天有效）。 */
    public static String sign(String path) {
        return sign(path, System.currentTimeMillis() + DEFAULT_TTL_MILLIS);
    }

    public static String sign(String path, long expiresAtMillis) {
        String sig = hmacHex(path + "|" + expiresAtMillis);
        return path + (path.contains("?") ? "&" : "?") + "exp=" + expiresAtMillis + "&sig=" + sig;
    }

    /** 校验签名是否有效且未过期。 */
    public static boolean verify(String path, long expiresAtMillis, String sig) {
        if (expiresAtMillis <= System.currentTimeMillis()) return false;
        if (sig == null || sig.isBlank()) return false;
        String expected = hmacHex(path + "|" + expiresAtMillis);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}