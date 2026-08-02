package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.video.dto.HanimeMetadata;
import com.fryfrog.hub.video.service.HanimeScraperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Hanime 视频解析接口
 * <p>
 * 提供 hanime1.me 视频解析服务，返回视频元数据和播放地址
 */
@RestController
@RequestMapping("/api/v1/hanime")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Hanime 视频解析", description = "解析 hanime1.me 视频，返回元数据和播放地址")
public class HanimeParseController {

    private final HanimeScraperService hanimeScraperService;

    @GetMapping("/parse")
    @Operation(summary = "解析视频", description = "解析 hanime1.me 视频，返回完整元数据和播放地址")
    public ResponseEntity<ApiResponse<HanimeMetadata>> parse(
            @Parameter(description = "视频ID（hanime1.me 的 v 参数）") @RequestParam String videoId) {
        log.info("Parsing Hanime video: {}", videoId);
        HanimeMetadata metadata = hanimeScraperService.scrapeWithSources(videoId);
        if (metadata == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("解析失败，请检查视频ID是否正确或稍后重试"));
        }
        return ResponseEntity.ok(ApiResponse.success(metadata));
    }

    @GetMapping("/metadata")
    @Operation(summary = "获取元数据", description = "仅获取视频元数据（不含播放地址，速度更快）")
    public ResponseEntity<ApiResponse<HanimeMetadata>> metadata(
            @Parameter(description = "视频ID") @RequestParam String videoId) {
        HanimeMetadata metadata = hanimeScraperService.scrape(videoId);
        if (metadata == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("获取失败，请检查视频ID是否正确或稍后重试"));
        }
        return ResponseEntity.ok(ApiResponse.success(metadata));
    }

    @GetMapping("/proxy")
    @Operation(summary = "代理视频流", description = "代理 hanime 视频流，解决跨域和防盗链问题")
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "视频URL") @RequestParam String url,
            @RequestHeader(value = "Range", required = false) String range) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .build();

            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://hanime1.me/");

            if (range != null) {
                requestBuilder.header("Range", range);
            }

            java.net.http.HttpResponse<byte[]> response = client.send(
                    requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
                    .status(response.statusCode())
                    .header("Content-Type", response.headers().firstValue("Content-Type").orElse("video/mp4"))
                    .header("Accept-Ranges", "bytes");

            response.headers().firstValue("Content-Range").ifPresent(cr -> responseBuilder.header("Content-Range", cr));
            response.headers().firstValue("Content-Length").ifPresent(cl -> responseBuilder.header("Content-Length", cl));

            return responseBuilder.body(response.body());

        } catch (Exception e) {
            log.error("Proxy video failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping("/check")
    @Operation(summary = "批量检查视频", description = "批量检查视频ID是否有效")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> check(
            @RequestBody java.util.List<String> videoIds) {
        java.util.Map<String, Boolean> results = new java.util.LinkedHashMap<>();
        for (String videoId : videoIds) {
            try {
                HanimeMetadata metadata = hanimeScraperService.scrape(videoId);
                results.put(videoId, metadata != null);
            } catch (Exception e) {
                results.put(videoId, false);
            }
        }
        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
