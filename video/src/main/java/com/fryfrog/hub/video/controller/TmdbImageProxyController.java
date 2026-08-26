package com.fryfrog.hub.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TMDB 图片本地代理：前端 <img> 不再直连 image.tmdb.org，
 * 统一经此接口中转并本地缓存，规避外网访问限制、集中控制流量。
 * 仅允许代理 TMDB 图片路径，防止被当作任意 URL 开放代理（SSRF）。
 */
@RestController
@RequestMapping("/api/v1/video/tmdb-image-proxy")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "TMDB 图片代理", description = "演员作品封面等 TMDB 图片本地代理与缓存")
public class TmdbImageProxyController {

    /** 允许的 TMDB 图片尺寸白名单 */
    private static final Set<String> ALLOWED_SIZES = Set.of(
            "w92", "w154", "w185", "w342", "w500", "w780", "original");

    private static final String CDN_BASE = "https://image.tmdb.org/t/p";

    /** 图片缓存根目录（工作目录下 data/ 与媒体密钥一致） */
    private final Path cacheRoot = Paths.get("data/tmdb-image-cache");

    /** 防止同一图片并发重复下载 */
    private static final Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();

    @GetMapping
    @Operation(summary = "代理 TMDB 图片", description = "按图片路径与尺寸代理返回 TMDB 图片，本地缓存")
    public ResponseEntity<org.springframework.core.io.Resource> proxy(
            @Parameter(description = "TMDB 图片路径，如 /abc.jpg") @RequestParam String path,
            @Parameter(description = "图片尺寸，白名单 w92/w154/w342/w500/w780/original") @RequestParam(defaultValue = "w500") String size) {
        if (!isSafePath(path) || !ALLOWED_SIZES.contains(size)) {
            return ResponseEntity.badRequest().build();
        }

        Path cached = resolveCacheFile(path, size);
        try {
            if (Files.exists(cached)) {
                return ok(cached);
            }
            if (!DOWNLOADING.add(cacheKey(path, size))) {
                // 另一个请求正在下载，等待后重读
                for (int i = 0; i < 100 && !Files.exists(cached); i++) {
                    Thread.sleep(100);
                }
                if (Files.exists(cached)) {
                    return ok(cached);
                }
                DOWNLOADING.remove(cacheKey(path, size));
                return ResponseEntity.status(502).build();
            }
            try {
                download(path, size, cached);
                if (Files.exists(cached)) {
                    return ok(cached);
                }
                return ResponseEntity.status(502).build();
            } finally {
                DOWNLOADING.remove(cacheKey(path, size));
            }
        } catch (Exception e) {
            log.warn("[TmdbImageProxy] Failed to proxy {} size={}: {}", path, size, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    private ResponseEntity<org.springframework.core.io.Resource> ok(Path cached) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        Files.probeContentType(cached) != null ? Files.probeContentType(cached) : "image/jpeg"))
                .body(new FileSystemResource(cached.toFile()));
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

    private String cacheKey(String path, String size) {
        return size + path;
    }

    private Path resolveCacheFile(String path, String size) {
        String safe = path.replaceAll("[^a-zA-Z0-9/._-]", "_");
        String fileName = safe.substring(safe.lastIndexOf('/') + 1);
        if (fileName.isBlank() || fileName.equals(".")) {
            fileName = "image.jpg";
        }
        String sizeDir = size == null || size.isBlank() ? "w500" : size;
        Path dir = cacheRoot.resolve(sizeDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[TmdbImageProxy] Cannot create cache dir: {}", e.getMessage());
        }
        // 用 hash 作为文件名避免路径中的 / 问题
        String hash = Integer.toHexString(cacheKey(path, size).hashCode());
        return dir.resolve(hash + "-" + fileName);
    }

    private void download(String path, String size, Path target) throws IOException {
        String url = CDN_BASE + "/" + size + path;
        URLConnection conn = new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "FryfrogHub/0.1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (var in = conn.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > 0) {
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}