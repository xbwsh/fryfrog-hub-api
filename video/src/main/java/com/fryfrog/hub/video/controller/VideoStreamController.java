package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.TranscodingService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频流播放", description = "视频流媒体播放、字幕与播放列表接口")
public class VideoStreamController {

    private final VideoService service;
    private final NfoService nfoService;
    private final TranscodingService transcodingService;
    private final VideoRepository videoRepository;

    /** M3U 等对外 URL 的基地址覆盖（反向代理/NAT 场景），环境变量 VIDEO_BASE_URL */
    @Value("${video.base-url:${VIDEO_BASE_URL:}}")
    private String baseUrlConfig;

    @GetMapping("/{id:\\d+}/stream")
    @Operation(summary = "视频流播放", description = "支持 Range 请求")
    public void streamVideo(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {

        Video video = service.getVideoById(id);
        File videoFile = new File(video.getFilePath());

        if (!videoFile.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contentType = getContentType(videoFile.getName());
        long fileLength = videoFile.length();

        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            writeFullVideo(response, videoFile, contentType, fileLength);
            return;
        }

        // 标准解析：支持 start-、-suffix、start-end；非法或多段范围返回 416
        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            sendRangeNotSatisfiable(response, fileLength);
            return;
        }
        if (ranges.size() != 1) {
            sendRangeNotSatisfiable(response, fileLength);
            return;
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(fileLength);
        long end = range.getRangeEnd(fileLength);
        if (start >= fileLength) {
            sendRangeNotSatisfiable(response, fileLength);
            return;
        }

        long contentLength = end - start + 1;

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileLength));
        response.setContentLengthLong(contentLength);

        try (var raf = new java.io.RandomAccessFile(videoFile, "r")) {
            raf.seek(start);
            try (var os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                long remaining = contentLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = raf.read(buffer, 0, toRead);
                    if (read == -1) break;
                    os.write(buffer, 0, read);
                    remaining -= read;
                }
                os.flush();
            }
        }
    }

    private void writeFullVideo(jakarta.servlet.http.HttpServletResponse response,
                                File videoFile, String contentType, long fileLength) throws IOException {
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(fileLength);
        try (var is = new java.io.FileInputStream(videoFile); var os = response.getOutputStream()) {
            is.transferTo(os);
        }
    }

    private void sendRangeNotSatisfiable(jakarta.servlet.http.HttpServletResponse response, long fileLength) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
        response.setHeader("Content-Range", "bytes */" + fileLength);
    }

    @GetMapping("/{id:\\d+}/stream/transcode")
    @Operation(summary = "视频转码流播放", description = "实时转码播放，支持 1080p/720p/480p 质量选择")
    public void streamVideoTranscoded(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "转码质量", example = "1080p") @RequestParam(defaultValue = "1080p") String quality,
            @Parameter(description = "最大码率", example = "8M") @RequestParam(required = false) String maxBitrate,
            @Parameter(description = "需要烧录到视频流的字幕文件名（浏览器不支持的字幕格式，如 ASS/PGS/VobSub）") @RequestParam(required = false) String subtitle,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {

        log.debug("[Transcode] Request: id={}, quality={}, maxBitrate={}, subtitle={}", id, quality, maxBitrate, subtitle);

        if (!transcodingService.isAvailable()) {
            log.warn("[Transcode] FFmpeg not available");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Transcoding not available");
            return;
        }

        Video video = service.getVideoById(id);
        File videoFile = new File(video.getFilePath());

        if (!videoFile.exists()) {
            log.warn("[Transcode] File not found: {}", video.getFilePath());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // 解析字幕文件（仅限视频目录下的外挂字幕），带路径穿越校验
        String subtitlePath = null;
        if (subtitle != null && !subtitle.isBlank()) {
            Path videoDir = Paths.get(video.getFilePath()).getParent();
            Path subPath = videoDir.resolve(subtitle).normalize();
            if (!subPath.startsWith(videoDir) || !Files.isRegularFile(subPath)) {
                log.warn("[Transcode] Invalid subtitle: {}", subtitle);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid subtitle");
                return;
            }
            subtitlePath = subPath.toString();
        }

        log.debug("[Transcode] Starting transcode: {} -> {} @ {} (subtitle={})", videoFile.getAbsolutePath(), quality, maxBitrate, subtitlePath != null ? "yes" : "no");

        response.setContentType("video/mp4");
        response.setHeader("Accept-Ranges", "none");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        try {
            TranscodingService.TranscodeResult result = transcodingService.transcode(videoFile.getAbsolutePath(), quality, maxBitrate, subtitlePath);
            log.debug("[Transcode] FFmpeg process started, streaming...");
            try {
                var os = response.getOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                while ((bytesRead = result.inputStream().read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    os.flush();
                    totalBytes += bytesRead;
                }
                log.debug("[Transcode] Streaming complete, total bytes: {}", totalBytes);
            } finally {
                result.close();
            }
        } catch (IOException e) {
            // 客户端断开连接（如切换画质）是正常行为
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Connection reset") || msg.contains("Broken pipe") || msg.contains("flush"))) {
                log.debug("[Transcode] Client disconnected (likely quality switch)");
            } else {
                log.error("[Transcode] IO error: {}", msg);
            }
        } catch (Exception e) {
            log.error("[Transcode] Error during transcoding: {}", e.getMessage(), e);
        }
    }

    @GetMapping("/{id:\\d+}/playlist.m3u")
    @Operation(summary = "生成系列播放列表", description = "返回同系列所有集数的 M3U 播放列表，可用 PotPlayer/IINA 等播放器打开")
    public ResponseEntity<Resource> getSeriesPlaylist(
            @Parameter(description = "视频ID") @PathVariable Long id,
            jakarta.servlet.http.HttpServletRequest request) {
        Video video = service.getVideoById(id);

        // 优先用配置的 base URL，否则自动检测服务器 IP
        String baseUrl = getServerBaseUrl(request);

        // 找同系列所有视频
        List<Video> siblings;
        if (video.getSeries() != null) {
            siblings = videoRepository.findBySeries(video.getSeries());
        } else if (video.getTmdbId() != null) {
            siblings = videoRepository.findAllByTmdbId(video.getTmdbId());
        } else {
            siblings = List.of(video);
        }

        // 按季数+集数排序
        siblings.sort((a, b) -> {
            int sa = a.getSeasonNumber() != null ? a.getSeasonNumber() : 1;
            int sb = b.getSeasonNumber() != null ? b.getSeasonNumber() : 1;
            if (sa != sb) return Integer.compare(sa, sb);
            int ea = a.getEpisodeNumber() != null ? a.getEpisodeNumber() : 1;
            int eb = b.getEpisodeNumber() != null ? b.getEpisodeNumber() : 1;
            return Integer.compare(ea, eb);
        });

        // 生成 M3U（绝对 URL，流地址带签名）
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        String seriesTitle = video.getSeriesName() != null ? video.getSeriesName() : video.getTitle();
        for (Video v : siblings) {
            String title = v.getTitle();
            if (v.getSeasonNumber() != null && v.getEpisodeNumber() != null) {
                title = String.format("S%02dE%02d - %s", v.getSeasonNumber(), v.getEpisodeNumber(), v.getTitle());
            }
            m3u.append("#EXTINF:-1,").append(title).append("\n");
            m3u.append(baseUrl).append(MediaUrlSigner.sign("/api/v1/video/" + v.getId() + "/stream")).append("\n");
        }

        byte[] content = m3u.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/x-mpegurl; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + seriesTitle + ".m3u\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(new ByteArrayResource(content));
    }

    private String getServerBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
        // 显式配置优先（反向代理/NAT 场景最可靠），环境变量 VIDEO_BASE_URL
        if (baseUrlConfig != null && !baseUrlConfig.isBlank()) {
            return baseUrlConfig.replaceAll("/+$", "");
        }

        // 反向代理透传头优先（X-Forwarded-Host 通常已含非标端口）
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) scheme = request.getScheme();
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            return scheme + "://" + forwardedHost;
        }

        // 如果请求来自 localhost/127.0.0.1，自动替换为局域网 IP
        String host = request.getServerName();
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            String lanIp = detectLocalIpv4();
            if (lanIp != null) host = lanIp;
        }
        int port;
        try {
            String xfPort = request.getHeader("X-Forwarded-Port");
            port = (xfPort != null && !xfPort.isBlank()) ? Integer.parseInt(xfPort.trim()) : -1;
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port <= 0) port = request.getServerPort();
        return scheme + "://" + host + ":" + port;
    }

    private String detectLocalIpv4() {
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                if (network.isLoopback() || !network.isUp()) continue;
                var addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        return "application/octet-stream";
    }

    @GetMapping("/{id:\\d+}/subtitles")
    @Operation(summary = "获取外挂字幕列表", description = "返回视频目录中可用的外挂字幕文件列表")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getSubtitles(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path videoDir = Paths.get(video.getFilePath()).getParent();

        List<Map<String, String>> subtitles = new ArrayList<>();
        if (videoDir == null) return ResponseEntity.ok(ApiResponse.success(subtitles));

        Set<String> subtitleExts = Set.of(".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx");

        try (var files = Files.list(videoDir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return subtitleExts.stream().anyMatch(ext -> name.endsWith(ext));
                    })
                    .forEach(f -> {
                        String name = f.getFileName().toString();
                        String lang = extractLanguageFromSubtitle(name);
                        Map<String, String> entry = new java.util.LinkedHashMap<>();
                        entry.put("filename", name);
                        entry.put("language", lang);
                        // URLEncoder 把空格编成 '+'，但 @PathVariable 解码时不把 '+' 当空格（客户端 404），
                        // 必须替换为 %20；字面 '+' 会被编码为 %2B 不受影响
                        entry.put("url", MediaUrlSigner.sign("/api/v1/video/" + id + "/subtitles/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")));
                        subtitles.add(entry);
                    });
        } catch (Exception e) {
            log.debug("[Subtitle] Failed to list subtitles for video {}: {}", id, e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(subtitles));
    }

    private String extractLanguageFromSubtitle(String filename) {
        String name = filename;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }
        int secondLastDot = name.lastIndexOf('.');
        if (secondLastDot > 0) {
            String lang = name.substring(secondLastDot + 1);
            if (!lang.isEmpty()) return lang;
        }
        return "und";
    }

    @GetMapping("/{id:\\d+}/subtitles/{filename:.+}")
    @Operation(summary = "获取字幕文件", description = "返回指定字幕文件的原始内容")
    public ResponseEntity<Resource> getSubtitleFile(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "字幕文件名") @PathVariable String filename) {
        Video video = service.getVideoById(id);
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        if (videoDir == null) return ResponseEntity.notFound().build();

        Path subPath = videoDir.resolve(filename).normalize();
        // 安全校验，防止路径穿越
        if (!subPath.startsWith(videoDir)) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(subPath)) {
            return ResponseEntity.notFound().build();
        }

        String lower = filename.toLowerCase();
        MediaType mediaType = MediaType.TEXT_PLAIN;
        if (lower.endsWith(".vtt")) mediaType = MediaType.parseMediaType("text/vtt");
        else if (lower.endsWith(".srt")) mediaType = MediaType.parseMediaType("text/plain; charset=utf-8");
        else if (lower.endsWith(".ass") || lower.endsWith(".ssa")) mediaType = MediaType.parseMediaType("text/plain; charset=utf-8");

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(subPath.toFile()));
    }
}
