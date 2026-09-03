package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.LogoOption;
import com.fryfrog.hub.video.dto.LogoSelectRequest;
import com.fryfrog.hub.video.dto.FrameSelectRequest;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.CoverArtService;
import com.fryfrog.hub.video.service.FrameCaptureService;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.TmdbService;
import com.fryfrog.hub.video.service.VideoAssetService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频图片资源", description = "封面、背景图、Logo、演员头像与截帧候选接口")
public class VideoImageController {

    private final VideoService service;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final FrameCaptureService frameCaptureService;
    private final MediaProbeService probeService;
    private final VideoActorRepository actorRepository;
    private final VideoRepository videoRepository;
    private final SeriesService seriesService;
    private final VideoAssetService assetService;
    private final TmdbService tmdbService;
    private final VideoControllerSupport support;

    /** 正在懒生成截帧的视频 ID（防止并发请求对同一视频重复起 FFmpeg） */
    private static final Set<Long> FRAME_GENERATING = ConcurrentHashMap.newKeySet();

    @GetMapping("/actor/{actorId:\\d+}/image")
    @Operation(summary = "获取演员头像", description = "返回指定演员的头像图片")
    public ResponseEntity<Resource> getActorImage(
            @Parameter(description = "演员ID") @PathVariable Long actorId) {
        VideoActor actor = actorRepository.findById(actorId).orElse(null);
        if (actor == null) {
            return ResponseEntity.notFound().build();
        }
        // 优先本地文件
        if (actor.getImagePath() != null) {
            Path imagePath = Paths.get(actor.getImagePath());
            if (Files.exists(imagePath)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(new FileSystemResource(imagePath.toFile()));
            }
        }
        // 兜底：本地缺失时代理 TMDB 远程图（actor.imageUrl 为 w185 真实地址，@JsonGetter 仅影响序列化）
        String remoteUrl = actor.getImageUrl();
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                java.net.URL url = new java.net.URL(remoteUrl);
                var conn = url.openConnection();
                conn.setRequestProperty("User-Agent", "FryfrogHub/0.1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (var in = conn.getInputStream()) {
                    byte[] bytes = in.readAllBytes();
                    if (bytes.length > 0) {
                        // 回填本地缓存，下次直接命中文件
                        if (actor.getImagePath() != null) {
                            try {
                                Path p = Paths.get(actor.getImagePath());
                                if (!Files.exists(p)) {
                                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                                    Files.write(p, bytes);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to cache actor image {}: {}", actorId, e.getMessage());
                            }
                        }
                        return ResponseEntity.ok()
                                .contentType(MediaType.IMAGE_JPEG)
                                .body(new ByteArrayResource(bytes));
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to proxy actor image {} from {}: {}", actorId, remoteUrl, e.getMessage());
            }
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{id:\\d+}/cover")
    @Operation(summary = "获取封面图片", description = "返回视频的封面图片（竖屏海报），无封面时返回标题占位图")
    public ResponseEntity<Resource> getCoverArt(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);

        // 优先使用数据库中已存的本地封面路径
        if (video.getCoverArtPath() != null) {
            Path stored = Paths.get(video.getCoverArtPath());
            if (Files.exists(stored)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(new FileSystemResource(stored.toFile()));
            }
        }

        // 兜底：检查元数据目录
        Path posterPath = nfoService.getPosterPath(video);
        if (Files.exists(posterPath)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new FileSystemResource(posterPath.toFile()));
        }

        // 兜底：未刮削视频从视频本身截帧作为封面（懒生成 + 缓存到视频同目录）
        try {
            Path videoDir = Paths.get(video.getFilePath()).getParent();
            if (videoDir != null) {
                String baseName = nfoService.getBaseName(video.getFileName());
                // v3: 多点采样+内容评分选帧；旧版单帧缓存（-frame.jpg / -frame-v2.jpg）不再使用
                Path framePath = videoDir.resolve(baseName + "-frame-v3.jpg");
                if (!Files.exists(framePath) && tryBeginFrameGeneration(id)) {
                    try {
                        frameCaptureService.extractFrame(video.getFilePath(), framePath.toString());
                    } finally {
                        endFrameGeneration(id);
                    }
                }
                if (Files.exists(framePath)) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(new FileSystemResource(framePath.toFile()));
                }
            }
        } catch (Exception e) {
            log.debug("Frame extraction failed for video {}: {}", id, e.getMessage());
        }

        try {
            byte[] placeholder = PlaceholderImageGenerator.generate(video.getTitle(), 300, 450);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(new ByteArrayResource(placeholder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id:\\d+}/fanart")
    @Operation(summary = "获取横屏背景图", description = "返回视频的横屏背景图")
    public ResponseEntity<Resource> getFanart(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);

        // 优先使用数据库中已存的本地背景图路径
        if (video.getBackdropLocalPath() != null) {
            Path stored = Paths.get(video.getBackdropLocalPath());
            if (Files.exists(stored)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(new FileSystemResource(stored.toFile()));
            }
        }

        // 兜底：检查元数据目录
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        Path fanartPath = videoDir.resolve(baseName + "-fanart.jpg");
        if (!Files.exists(fanartPath)) {
            fanartPath = nfoService.getFanartPath(video);
        }
        if (!Files.exists(fanartPath)) {
            // 兜底：未刮削视频从视频本身截取横屏帧作为背景图（懒生成 + 缓存到视频同目录）
            try {
                if (videoDir != null) {
                    // v3: 多点采样+内容评分选帧；旧版单帧缓存（-fanart-frame.jpg / -v2.jpg）不再使用
                    Path framePath = videoDir.resolve(baseName + "-fanart-frame-v3.jpg");
                    if (!Files.exists(framePath) && tryBeginFrameGeneration(id)) {
                        try {
                            frameCaptureService.extractFrame(video.getFilePath(), framePath.toString(), 1920, 1080);
                        } finally {
                            endFrameGeneration(id);
                        }
                    }
                    if (Files.exists(framePath)) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.IMAGE_JPEG)
                                .body(new FileSystemResource(framePath.toFile()));
                    }
                }
            } catch (Exception e) {
                log.debug("Fanart frame extraction failed for video {}: {}", id, e.getMessage());
            }
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(fanartPath.toFile()));
    }

    @GetMapping("/{id:\\d+}/logo")
    @Operation(summary = "获取电影Logo", description = "返回电影的字标 logo 图片（本地优先，远程 TMDB 兜底）")
    public ResponseEntity<Resource> getMovieLogo(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);

        // 优先使用本地 logo 文件
        if (video.getLogoLocalPath() != null) {
            Path stored = Paths.get(video.getLogoLocalPath());
            if (Files.exists(stored)) {
                return ResponseEntity.ok()
                        .contentType(logoMediaType(stored.getFileName().toString()))
                        .body(new FileSystemResource(stored.toFile()));
            }
        }

        // 兜底：从 TMDB 远程下载
        String logoUrl = video.getLogoUrl();
        if (logoUrl == null && video.getTmdbId() != null) {
            logoUrl = tmdbService.getMovieLogoUrl(video.getTmdbId());
        }
        if (logoUrl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            java.net.URL url = new java.net.URL(logoUrl);
            byte[] imageBytes = url.openStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(logoMediaType(logoUrl))
                    .body(new ByteArrayResource(imageBytes));
        } catch (Exception e) {
            log.debug("Failed to download movie logo from TMDB: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType logoMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.IMAGE_JPEG;
    }

    @GetMapping("/{id:\\d+}/logo-options")
    @Operation(summary = "查询电影Logo选项", description = "返回该电影在 TMDB 上的所有字标 logo 选项（按投票数降序），供用户选择设置")
    public ResponseEntity<ApiResponse<List<LogoOption>>> getMovieLogoOptions(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        if (video.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("视频没有 TMDB ID，无法查询 logo"));
        }
        return ResponseEntity.ok(ApiResponse.success(tmdbService.getMovieLogoOptions(video.getTmdbId())));
    }

    @PostMapping("/{id:\\d+}/logo")
    @Operation(summary = "设置电影Logo", description = "从查询到的 logo 选项中选一个设置（body 传 filePath）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setMovieLogo(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody LogoSelectRequest request,
            HttpServletRequest httpRequest) {
        support.requireAdmin(httpRequest);
        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("filePath 不能为空"));
        }

        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        if (video.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("视频没有 TMDB ID，无法设置 logo"));
        }

        try {
            boolean downloaded = assetService.downloadMovieLogo(video, request.getFilePath());
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("videoId", id);
            result.put("title", video.getTitle());
            result.put("downloaded", downloaded);
            result.put("logoUrl", video.getLogoApiUrl());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to set logo for video {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("设置 logo 失败: " + e.getMessage()));
        }
    }

    /**
     * 截帧候选缓存目录：视频同目录下 .frames-{videoId}/
     */
    private Path getFramesCacheDir(Video video) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        return videoDir.resolve(".frames-" + video.getId());
    }

    /** 尝试占用生成权；已在生成的视频直接跳过，由兜底占位图响应 */
    private boolean tryBeginFrameGeneration(Long videoId) {
        return FRAME_GENERATING.add(videoId);
    }

    private void endFrameGeneration(Long videoId) {
        FRAME_GENERATING.remove(videoId);
    }

    @PostMapping("/{id:\\d+}/frames")
    @Operation(summary = "生成截帧候选列表", description = "截取视频多个位置的关键帧作为封面候选，返回候选列表供前端预览选择")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateFrameCandidates(
            @Parameter(description = "视频ID") @PathVariable Long id, HttpServletRequest request) {
        support.requireAdmin(request);
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        Path cacheDir = getFramesCacheDir(video);
        try {
            // 清理旧候选
            if (Files.exists(cacheDir)) {
                try (var walk = Files.walk(cacheDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
            Files.createDirectories(cacheDir);

            double duration = probeService.getDurationSeconds(video.getFilePath());
            // 6 个采样位置（避开片头片尾）
            double[] ratios = {0.12, 0.28, 0.44, 0.60, 0.76, 0.88};

            List<Map<String, Object>> candidates = new ArrayList<>();
            for (int i = 0; i < ratios.length; i++) {
                double pos = duration > 0 ? duration * ratios[i] : 30 + i * 30;
                Path framePath = cacheDir.resolve("frame-" + i + ".jpg");
                if (frameCaptureService.captureFrameAt(video.getFilePath(), framePath.toString(), 640, 360, pos)) {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("index", i);
                    item.put("position", Math.round(pos));
                    item.put("url", "/api/v1/video/" + id + "/frames/" + i);
                    candidates.add(item);
                }
            }

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("videoId", id);
            result.put("total", candidates.size());
            result.put("candidates", candidates);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to generate frame candidates for video {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("截帧候选生成失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{id:\\d+}/frames/{index:\\d+}")
    @Operation(summary = "获取候选帧图片", description = "返回指定索引的候选帧图片（预览用）")
    public ResponseEntity<Resource> getFrameCandidate(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "候选帧索引") @PathVariable int index) {
        Video video = service.getVideoById(id);
        Path framePath = getFramesCacheDir(video).resolve("frame-" + index + ".jpg");
        if (!Files.exists(framePath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(framePath.toFile()));
    }

    @PostMapping("/{id:\\d+}/frames/select")
    @Operation(summary = "选定截帧作为封面", description = "将指定候选帧设置为视频的竖屏封面、横屏背景图，或所属系列的横屏背景图")
    public ResponseEntity<ApiResponse<Map<String, Object>>> selectFrame(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody FrameSelectRequest request,
            HttpServletRequest httpRequest) {
        support.requireAdmin(httpRequest);
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        Path cacheDir = getFramesCacheDir(video);
        Path framePath = cacheDir.resolve("frame-" + request.getIndex() + ".jpg");
        if (!Files.exists(framePath)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("候选帧不存在，请先调用生成接口"));
        }

        String type = request.getType();
        boolean isPoster = "poster".equalsIgnoreCase(type);
        boolean isSeriesFanart = "series_fanart".equalsIgnoreCase(type);

        // 系列横屏背景图：视频必须属于某个系列
        VideoSeries series = null;
        if (isSeriesFanart) {
            series = video.getSeries();
            if (series == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("该视频不属于任何系列，无法设置为系列背景图"));
            }
        }

        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        String outputName;
        if (isPoster) {
            outputName = baseName + "-frame-v3.jpg";
        } else if (isSeriesFanart) {
            outputName = baseName + "-series-fanart.jpg";
        } else {
            outputName = baseName + "-fanart-frame-v3.jpg";
        }
        Path outputPath = videoDir.resolve(outputName);

        try {
            // 从候选帧对应的原始时间点重新截取目标尺寸
            double duration = probeService.getDurationSeconds(video.getFilePath());
            double[] ratios = {0.12, 0.28, 0.44, 0.60, 0.76, 0.88};
            double pos = duration > 0 ? duration * ratios[request.getIndex()] : 30 + request.getIndex() * 30;
            boolean ok = frameCaptureService.captureFrameAt(
                    video.getFilePath(), outputPath.toString(),
                    isPoster ? 300 : 1920, isPoster ? 450 : 1080, pos);
            if (!ok) {
                // 兜底：直接复制候选帧
                Files.copy(framePath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }

            if (isPoster) {
                video.setCoverArtPath(outputPath.toString());
                videoRepository.save(video);
            } else if (isSeriesFanart) {
                series.setBackdropLocalPath(outputPath.toString());
                seriesService.saveSeries(series);
            } else {
                video.setBackdropLocalPath(outputPath.toString());
                videoRepository.save(video);
            }

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("videoId", id);
            result.put("type", type);
            result.put("path", outputPath.toString());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to select frame for video {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("封面设置失败: " + e.getMessage()));
        }
    }
}
