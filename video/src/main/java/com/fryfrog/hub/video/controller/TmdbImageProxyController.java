package com.fryfrog.hub.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLConnection;
import java.util.Set;

/**
 * TMDB 图片纯代理：前端 &lt;img&gt; 不再直连 image.tmdb.org，
 * 统一经此接口中转，缓存交给浏览器（Cache-Control immutable，TMDB 图片 URL 内容不变）。
 * 仅允许代理 TMDB 图片路径，防止被当作任意 URL 开放代理（SSRF）。
 */
@RestController
@RequestMapping("/api/v1/video/tmdb-image-proxy")
@Slf4j
@Tag(name = "TMDB 图片代理", description = "演员作品封面等 TMDB 图片代理")
public class TmdbImageProxyController {

    /** 允许的 TMDB 图片尺寸白名单 */
    private static final Set<String> ALLOWED_SIZES = Set.of(
            "w92", "w154", "w185", "w342", "w500", "w780", "original");

    private static final String CDN_BASE = "https://image.tmdb.org/t/p";

    /** 浏览器缓存：7 天 + immutable，重复访问零回源 */
    private static final String CLIENT_CACHE = "public, max-age=604800, immutable";

    @GetMapping
    @Operation(summary = "代理 TMDB 图片", description = "按图片路径与尺寸代理返回 TMDB 图片，由浏览器缓存")
    public ResponseEntity<ByteArrayResource> proxy(
            @Parameter(description = "TMDB 图片路径，如 /abc.jpg") @RequestParam String path,
            @Parameter(description = "图片尺寸，白名单 w92/w154/w342/w500/w780/original") @RequestParam(defaultValue = "w500") String size) {
        if (!isSafePath(path) || !ALLOWED_SIZES.contains(size)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] bytes = download(path, size);
            if (bytes.length == 0) {
                return ResponseEntity.status(502).build();
            }
            return ResponseEntity.ok()
                    .contentType(contentTypeOf(path))
                    .header(HttpHeaders.CACHE_CONTROL, CLIENT_CACHE)
                    .body(new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.warn("[TmdbImageProxy] Failed to proxy {} size={}: {}", path, size, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    /** 校验 path 必须是 TMDB 图片相对路径：/开头、无 ..、无协议/域名 */
    private boolean isSafePath(String path) {
        if (path == null || path.isBlank()) return false;
        if (!path.startsWith("/")) return false;
        if (path.contains("..")) return false;
        if (path.contains("://")) return false;
        if (path.length() > 512) return false;
        return true;
    }

    private static MediaType contentTypeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        return MediaType.IMAGE_JPEG;
    }

    private byte[] download(String path, String size) throws java.io.IOException {
        String url = CDN_BASE + "/" + size + path;
        URLConnection conn = new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "FryfrogHub/0.1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (var in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
