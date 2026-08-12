package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.util.PlaceholderImageGenerator;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.VideoBindRequest;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.dto.UpdatePositionRequest;
import com.fryfrog.hub.video.dto.UpdateWatchedRequest;
import com.fryfrog.hub.video.dto.WatchProgressDTO;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoActor;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.CoverArtService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.TmdbService;
import com.fryfrog.hub.video.service.TranscodingService;
import com.fryfrog.hub.video.service.VideoAssetService;
import com.fryfrog.hub.video.service.VideoOrganizeService;
import com.fryfrog.hub.video.service.VideoScrapeService;
import com.fryfrog.hub.video.service.VideoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频管理", description = "视频元数据查询、扫描接口")
public class VideoController {

    private final VideoService service;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final WatchProgressService watchProgressService;
    private final TranscodingService transcodingService;
    private final VideoActorRepository actorRepository;
    private final VideoRepository videoRepository;
    private final ScrapeProgressService scrapeProgressService;
    private final VideoOrganizeService organizeService;
    private final VideoAssetService assetService;
    private final SeriesService seriesService;
    private final PeriodicScanScheduler scanScheduler;
    private final VideoScrapeService scrapeService;
    private final MediaLibraryService mediaLibraryService;
    private final TmdbService tmdbService;

    @GetMapping("/{id:\\d+}")
    @Operation(summary = "获取视频详情", description = "根据ID获取单个视频的详细信息")
    public ResponseEntity<ApiResponse<VideoDTO>> getVideoById(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @PutMapping("/{id:\\d+}/metadata")
    @Operation(summary = "编辑视频元数据", description = "手动修改视频的标题、简介、评分、上映日期、类型等元数据（只更新传入的非空字段）")
    public ResponseEntity<ApiResponse<VideoDTO>> updateVideoMetadata(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody com.fryfrog.hub.video.dto.VideoMetadataUpdateRequest request) {
        Video video = service.getVideoById(id);
        boolean updated = false;

        if (request.getTitle() != null) { video.setTitle(request.getTitle()); updated = true; }
        if (request.getOverview() != null) { video.setOverview(request.getOverview()); updated = true; }
        if (request.getRating() != null) { video.setRating(request.getRating()); updated = true; }
        if (request.getYear() != null) { video.setYear(request.getYear()); updated = true; }
        if (request.getReleaseDate() != null) { video.setReleaseDate(request.getReleaseDate()); updated = true; }
        if (request.getGenre() != null) { video.setGenre(request.getGenre()); updated = true; }
        if (request.getDirector() != null) { video.setDirector(request.getDirector()); updated = true; }
        if (request.getActors() != null) { video.setActors(request.getActors()); updated = true; }
        if (request.getOriginalTitle() != null) { video.setOriginalTitle(request.getOriginalTitle()); updated = true; }
        if (request.getTags() != null) { video.setTags(request.getTags()); updated = true; }

        if (updated) {
            video.setMetadataSource("manual");
            videoRepository.save(video);
            log.info("[Metadata] Updated video id={}: manual metadata applied", id);
        }
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @GetMapping("/search/title")
    @Operation(summary = "按标题搜索", description = "根据标题关键词模糊搜索视频")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> searchByTitle(
            @Parameter(description = "搜索关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.searchByTitle(q, page, size);
        List<Long> videoIds = result.getContent().stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(videoIds);
        List<VideoDTO> dtos = result.getContent().stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/search/director")
    @Operation(summary = "按导演搜索", description = "根据导演名称模糊搜索视频")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> searchByDirector(
            @Parameter(description = "导演名称关键词") @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.searchByDirector(q, page, size);
        List<Long> videoIds = result.getContent().stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(videoIds);
        List<VideoDTO> dtos = result.getContent().stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @GetMapping("/favorites")
    @Operation(summary = "获取收藏列表", description = "返回已收藏的视频，支持分页")
    public ResponseEntity<ApiResponse<PageResponse<VideoDTO>>> getFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.getFavorites(page, size);
        List<Long> videoIds = result.getContent().stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(videoIds);
        List<VideoDTO> dtos = result.getContent().stream()
                .map(v -> toDTO(v, progressMap.get(v.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(dtos, page, size, result.getTotalElements())));
    }

    @PutMapping("/{id:\\d+}/favorite")
    @Operation(summary = "设置收藏状态", description = "设置视频的收藏状态")
    public ResponseEntity<ApiResponse<VideoDTO>> setFavorite(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status) {
        Video video = service.setFavorite(id, status);
        return ResponseEntity.ok(ApiResponse.success(toDTO(video)));
    }

    @GetMapping("/{id:\\d+}/actors")
    @Operation(summary = "获取视频演员列表", description = "返回指定视频的演员信息列表")
    public ResponseEntity<ApiResponse<List<VideoActor>>> getActors(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(actorRepository.findByVideo_Id(id)));
    }

    @GetMapping("/actor/{actorId:\\d+}/image")
    @Operation(summary = "获取演员头像", description = "返回指定演员的头像图片")
    public ResponseEntity<Resource> getActorImage(
            @Parameter(description = "演员ID") @PathVariable Long actorId) {
        VideoActor actor = actorRepository.findById(actorId).orElse(null);
        if (actor == null || actor.getImagePath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path imagePath = Paths.get(actor.getImagePath());
        if (!Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(imagePath.toFile()));
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
                if (!Files.exists(framePath)) {
                    transcodingService.extractFrame(video.getFilePath(), framePath.toString());
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

    @GetMapping("/tmdb/search")
    @Operation(summary = "搜索TMDB", description = "根据关键词在TMDB上搜索电影和电视剧")
    public ResponseEntity<ApiResponse<List<TmdbSearchResult.TmdbSearchItem>>> searchTmdb(
            @Parameter(description = "搜索关键词") @RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchFromTmdb(q)));
    }

    @PostMapping("/{id:\\d+}/tmdb/bind")
    @Operation(summary = "绑定TMDB元数据", description = "绑定整个系列（同标题的所有视频）到指定TMDB条目，并重命名文件（异步执行，进度见 scrape/progress?module=bind:{id}）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bindTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody VideoBindRequest request) {
        log.info("[Bind] Binding video id={} to TMDB {} ({})", id, request.getTmdbId(), request.getMediaType());

        String module = "bind:" + id;
        // 在异步线程启动前先注册进度模块，避免前端轮询竞态拿到空对象误判完成
        scrapeProgressService.start(module, 1);

        // 暂停 periodic-scan，防止并发冲突；异步执行，避免前端长时间无响应
        scanScheduler.setBusy(true);
        Thread.startVirtualThread(() -> {
            try {
                // 1. 绑定整个系列（isAdult 由 doScrapeAndBind 自动从 TMDB 判断）
                List<Video> boundVideos = service.bindSeries(id, request.getTmdbId(), request.getMediaType(), false);

                // 2. 重命名文件 + 移动到元数据目录
                scrapeProgressService.stage(module, "organize");
                organizeService.batchOrganize(boundVideos);

                // 3. 生成 NFO + 下载封面（强制覆盖）
                scrapeProgressService.stage(module, "assets");
                assetService.batchGenerateAssets(boundVideos, true);

                // 4. 清理空的 series 记录
                seriesService.cleanupEmptySeries();

                scrapeProgressService.stage(module, "done");
                scrapeProgressService.finish(module);
            } catch (Exception e) {
                log.error("[Bind] Failed to bind video id={}: {}", id, e.getMessage(), e);
                scrapeProgressService.stage(module, "error");
                scrapeProgressService.finish(module);
            } finally {
                scanScheduler.setBusy(false);
            }
        });

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "started");
        result.put("videoId", id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id:\\d+}/tmdb/unbind")
    @Operation(summary = "解绑TMDB元数据", description = "解绑该视频所属系列的所有视频（同tmdbId）的TMDB元数据")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unbindTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        log.info("[Unbind] Request to unbind video id={}, title='{}', tmdbId={}",
                id, video.getTitle(), video.getTmdbId());
        if (video.getTmdbId() == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("unbound", 0)));
        }
        Long tmdbId = video.getTmdbId();
        int count = service.unbindByTmdbId(tmdbId);
        log.info("[Unbind] Unbound {} videos with tmdbId={}", count, tmdbId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "tmdbId", tmdbId,
                "unbound", count
        )));
    }

    @PostMapping("/{id:\\d+}/tmdb/refresh")
    @Operation(summary = "刷新TMDB元数据", description = "重新搜索TMDB并绑定，同时重命名文件（异步执行，进度见 scrape/progress?module=bind:{id}）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshTmdb(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        String module = "bind:" + id;
        // 在异步线程启动前先注册进度模块，避免前端轮询竞态拿到空对象误判完成
        scrapeProgressService.start(module, 1);

        // 暂停 periodic-scan，防止并发冲突；异步执行，避免前端长时间无响应
        scanScheduler.setBusy(true);
        Thread.startVirtualThread(() -> {
            try {
                List<Video> results = service.rescrapeVideo(id);

                // 重命名文件
                scrapeProgressService.stage(module, "organize");
                organizeService.batchOrganize(results);

                // 生成 NFO + 下载封面（强制覆盖）
                scrapeProgressService.stage(module, "assets");
                assetService.batchGenerateAssets(results, true);

                scrapeProgressService.stage(module, "done");
                scrapeProgressService.finish(module);
            } catch (Exception e) {
                log.error("[Refresh] Failed to refresh video id={}: {}", id, e.getMessage(), e);
                scrapeProgressService.stage(module, "error");
                scrapeProgressService.finish(module);
            } finally {
                scanScheduler.setBusy(false);
            }
        });

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "started");
        result.put("videoId", id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/tmdb/rescrape-library/{libraryId}")
    @Operation(summary = "按资源库重新刮削", description = "解绑指定资源库中所有视频的TMDB绑定，然后根据资源库类型重新搜索绑定")
    public ResponseEntity<ApiResponse<String>> rescrapeByLibrary(
            @Parameter(description = "资源库ID") @PathVariable Long libraryId) {
        service.rescrapeByLibrary(libraryId);
        return ResponseEntity.ok(ApiResponse.success("Rescrape started for library " + libraryId));
    }

    @PostMapping("/refresh-all-actors")
    @Operation(summary = "批量刷新演员", description = "异步刷新所有开启刮削媒体库中已绑定 TMDB 的视频的演员信息（含电影和剧集），进度查询见 scrape/progress?module=actors")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllActors() {
        // 获取启用刮削的媒体库 ID
        List<Long> scrapeEnabledLibraryIds = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> Boolean.TRUE.equals(lib.getEnableScraping()))
                .map(lib -> lib.getId())
                .toList();

        // 所有开启刮削媒体库中已绑定 TMDB 的视频（电影 + 剧集）
        List<Video> videosWithTmdb = videoRepository.findAll().stream()
                .filter(v -> v.getTmdbId() != null && v.getMediaType() != null)
                .filter(v -> v.getLibraryId() != null && scrapeEnabledLibraryIds.contains(v.getLibraryId()))
                .toList();

        String module = "actors";
        scrapeProgressService.start(module, videosWithTmdb.size());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalVideos", videosWithTmdb.size());
        result.put("status", "submitted");
        result.put("message", "批量刷新演员任务已提交，正在后台执行");
        result.put("module", module);

        // 异步执行批量刷新
        Thread.startVirtualThread(() -> {
            int completed = 0;
            int failed = 0;

            for (Video video : videosWithTmdb) {
                try {
                    assetService.saveActors(video, video.getMediaType(), video.getTmdbId(), null);
                    completed++;
                    scrapeProgressService.advance(module, video.getTitle(), true);
                    log.info("[Batch] Completed {}/{}: {}", completed, videosWithTmdb.size(), video.getTitle());
                } catch (Exception e) {
                    failed++;
                    scrapeProgressService.advance(module, video.getTitle(), false);
                    log.warn("[Batch] Failed video {}: {}", video.getTitle(), e.getMessage());
                }
            }

            scrapeProgressService.finish(module);
            log.info("[Batch] Actors refresh done: {} completed, {} failed", completed, failed);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id:\\d+}/refresh-logo")
    @Operation(summary = "补全电影Logo", description = "为已绑定 TMDB 的电影从 TMDB 获取并下载字标 logo（本地已有则跳过）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshMovieLogo(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        if (video.getTmdbId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("视频没有 TMDB ID，无法获取 logo"));
        }

        try {
            boolean downloaded = assetService.downloadMovieLogo(video);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("videoId", id);
            result.put("title", video.getTitle());
            result.put("downloaded", downloaded);
            result.put("logoUrl", video.getLogoApiUrl());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.warn("Failed to refresh logo for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("补全 logo 失败: " + e.getMessage()));
        }
    }

    @PostMapping("/refresh-all-logos")
    @Operation(summary = "批量补全所有Logo", description = "异步为所有开启刮削媒体库中已绑定 TMDB 的系列和电影从 TMDB 获取并下载字标 logo，进度查询见 scrape/progress?module=logo:all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllLogos() {
        // 获取启用刮削的媒体库 ID
        List<Long> scrapeEnabledLibraryIds = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> Boolean.TRUE.equals(lib.getEnableScraping()))
                .map(lib -> lib.getId())
                .toList();

        // 开启刮削媒体库中已绑定 TMDB 的系列
        List<com.fryfrog.hub.video.model.VideoSeries> seriesWithTmdb = seriesService.getAllSeries().stream()
                .filter(s -> s.getTmdbId() != null)
                .filter(s -> s.getVideos().stream()
                        .anyMatch(v -> v.getLibraryId() != null && scrapeEnabledLibraryIds.contains(v.getLibraryId())))
                .toList();
        // 开启刮削媒体库中已绑定 TMDB 的独立电影
        List<Video> moviesWithTmdb = videoRepository.findBySeriesIsNullOrderByTitleAsc().stream()
                .filter(v -> v.getTmdbId() != null && "movie".equalsIgnoreCase(v.getMediaType()))
                .filter(v -> v.getLibraryId() != null && scrapeEnabledLibraryIds.contains(v.getLibraryId()))
                .toList();

        int total = seriesWithTmdb.size() + moviesWithTmdb.size();
        String module = "logo:all";
        scrapeProgressService.start(module, total);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalSeries", seriesWithTmdb.size());
        result.put("totalMovies", moviesWithTmdb.size());
        result.put("total", total);
        result.put("status", "submitted");
        result.put("message", "批量补全 logo 任务已提交，正在后台执行");
        result.put("module", module);

        Thread.startVirtualThread(() -> {
            for (com.fryfrog.hub.video.model.VideoSeries series : seriesWithTmdb) {
                try {
                    boolean ok = assetService.downloadSeriesLogo(series);
                    scrapeProgressService.advance(module, series.getTitle(), ok);
                    log.info("[LogoBatch] Series: {}", series.getTitle());
                } catch (Exception e) {
                    scrapeProgressService.advance(module, series.getTitle(), false);
                    log.warn("[LogoBatch] Failed series {}: {}", series.getTitle(), e.getMessage());
                }
            }
            for (Video movie : moviesWithTmdb) {
                try {
                    boolean ok = assetService.downloadMovieLogo(movie);
                    scrapeProgressService.advance(module, movie.getTitle(), ok);
                    log.info("[LogoBatch] Movie: {}", movie.getTitle());
                } catch (Exception e) {
                    scrapeProgressService.advance(module, movie.getTitle(), false);
                    log.warn("[LogoBatch] Failed movie {}: {}", movie.getTitle(), e.getMessage());
                }
            }
            scrapeProgressService.finish(module);
            log.info("[LogoBatch] All done: {} series, {} movies", seriesWithTmdb.size(), moviesWithTmdb.size());
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/refresh-all-resolutions")
    @Operation(summary = "批量补全视频分辨率", description = "异步为所有缺少分辨率的视频用 ffprobe 探测并补齐，只更新 resolution 字段，进度查询见 scrape/progress?module=resolution")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllResolutions() {
        List<Video> allVideos = videoRepository.findAll();
        List<Video> missingResolution = allVideos.stream()
                .filter(v -> v.getResolution() == null || v.getResolution().isBlank())
                .toList();

        String module = "resolution";
        scrapeProgressService.start(module, missingResolution.size());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalVideos", allVideos.size());
        result.put("pendingVideos", missingResolution.size());
        result.put("status", "submitted");
        result.put("message", "批量补全分辨率任务已提交，正在后台执行");
        result.put("module", module);

        Thread.startVirtualThread(() -> {
            int updated = 0;
            for (Video video : missingResolution) {
                try {
                    String resolution = transcodingService.probeResolution(video.getFilePath());
                    if (resolution != null) {
                        video.setResolution(resolution);
                        videoRepository.save(video);
                        updated++;
                    }
                    scrapeProgressService.advance(module, video.getFileName(), resolution != null);
                } catch (Exception e) {
                    scrapeProgressService.advance(module, video.getFileName(), false);
                    log.warn("[Resolution] Failed video {}: {}", video.getFileName(), e.getMessage());
                }
            }
            scrapeProgressService.finish(module);
            log.info("[Resolution] Done: {} videos, {} updated", missingResolution.size(), updated);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/scrape/progress")
    @Operation(summary = "刮削进度", description = "返回指定模块的刮削进度，module 可选: video/actors/logo:all/resolution/season-covers，默认 video")
    public ResponseEntity<ApiResponse<ScrapeProgress>> scrapeProgress(
            @Parameter(description = "进度模块名，如 actors、logo:all、resolution、season-covers") @RequestParam(required = false) String module) {
        String key = module != null && !module.isBlank() ? module : "video";
        return ResponseEntity.ok(ApiResponse.success(scrapeProgressService.getProgress(key)));
    }

    @PostMapping("/{id:\\d+}/nfo")
    @Operation(summary = "生成NFO文件", description = "为指定视频生成NFO元数据文件")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateNfo(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        String nfoPath = nfoService.generateNfo(video);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "nfoPath", nfoPath != null ? nfoPath : "null"
        )));
    }

    @PostMapping("/{id:\\d+}/covers")
    @Operation(summary = "下载封面图片", description = "下载视频的竖屏海报和横屏背景图")
    public ResponseEntity<ApiResponse<Map<String, String>>> downloadCovers(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        boolean success = coverArtService.downloadAllCovers(video, true);
        if (success) {
            videoRepository.save(video);
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "success", String.valueOf(success)
        )));
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
                    if (!Files.exists(framePath)) {
                        transcodingService.extractFrame(video.getFilePath(), framePath.toString(), 1920, 1080);
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
    public ResponseEntity<ApiResponse<List<com.fryfrog.hub.video.dto.LogoOption>>> getMovieLogoOptions(
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
            @RequestBody com.fryfrog.hub.video.dto.LogoSelectRequest request) {
        if (request.getFilePath() == null || request.getFilePath().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("filePath 不能为空"));
        }

        Video video = service.getVideoById(id);
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

    @PostMapping("/{id:\\d+}/frames")
    @Operation(summary = "生成截帧候选列表", description = "截取视频多个位置的关键帧作为封面候选，返回候选列表供前端预览选择")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateFrameCandidates(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
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

            double duration = transcodingService.getDurationSeconds(video.getFilePath());
            // 6 个采样位置（避开片头片尾）
            double[] ratios = {0.12, 0.28, 0.44, 0.60, 0.76, 0.88};

            List<Map<String, Object>> candidates = new ArrayList<>();
            for (int i = 0; i < ratios.length; i++) {
                double pos = duration > 0 ? duration * ratios[i] : 30 + i * 30;
                Path framePath = cacheDir.resolve("frame-" + i + ".jpg");
                if (transcodingService.captureFrameAt(video.getFilePath(), framePath.toString(), 640, 360, pos)) {
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
            @RequestBody com.fryfrog.hub.video.dto.FrameSelectRequest request) {
        Video video = service.getVideoById(id);
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
            double duration = transcodingService.getDurationSeconds(video.getFilePath());
            double[] ratios = {0.12, 0.28, 0.44, 0.60, 0.76, 0.88};
            double pos = duration > 0 ? duration * ratios[request.getIndex()] : 30 + request.getIndex() * 30;
            boolean ok = transcodingService.captureFrameAt(
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

    @GetMapping("/{id:\\d+}/nfo")
    @Operation(summary = "获取NFO内容", description = "返回视频的NFO文件内容")
    public ResponseEntity<ApiResponse<String>> getNfoContent(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path nfoPath = nfoService.getNfoPath(video);
        if (!Files.exists(nfoPath)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = Files.readString(nfoPath);
            return ResponseEntity.ok(ApiResponse.success(content));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id:\\d+}/progress")
    @Operation(summary = "获取观看进度", description = "获取指定视频的观看进度")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> getProgress(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        WatchProgress progress = watchProgressService.getProgress(id);
        if (progress == null) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/progress")
    @Operation(summary = "更新播放位置", description = "轻量更新播放位置，可选更新总时长。退出播放器时调用，自动判定是否看完")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> updatePosition(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @Valid @RequestBody UpdatePositionRequest request) {
        WatchProgress progress = watchProgressService.updatePosition(id, request.getPosition(), request.getDuration());
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @PutMapping("/{id:\\d+}/watched")
    @Operation(summary = "设置已观看状态", description = "标记视频为已看完或未看完")
    public ResponseEntity<ApiResponse<WatchProgressDTO>> updateWatched(
            @Parameter(description = "视频ID") @PathVariable Long id,
            @RequestBody UpdateWatchedRequest request) {
        boolean completed = request != null && Boolean.TRUE.equals(request.getCompleted());
        WatchProgress progress = watchProgressService.updateWatched(id, completed);
        return ResponseEntity.ok(ApiResponse.success(WatchProgressDTO.fromEntity(progress)));
    }

    @DeleteMapping("/{id:\\d+}/progress")
    @Operation(summary = "清除观看进度", description = "删除指定视频的观看进度记录")
    public ResponseEntity<ApiResponse<Void>> deleteProgress(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        watchProgressService.deleteProgress(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/{id:\\d+}/subtitles")
    @Operation(summary = "获取外挂字幕列表", description = "返回视频目录中可用的外挂字幕文件列表")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getSubtitles(
            @Parameter(description = "视频ID") @PathVariable Long id) {
        Video video = service.getVideoById(id);
        Path videoDir = Paths.get(video.getFilePath()).getParent();

        List<Map<String, String>> subtitles = new ArrayList<>();
        if (videoDir == null) return ResponseEntity.ok(ApiResponse.success(subtitles));

        java.util.Set<String> subtitleExts = java.util.Set.of(".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx");

        try (var files = java.nio.file.Files.list(videoDir)) {
            files.filter(java.nio.file.Files::isRegularFile)
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
                        entry.put("url", "/api/v1/video/" + id + "/subtitles/" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
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
            response.setContentType(contentType);
            response.setHeader("Accept-Ranges", "bytes");
            response.setContentLengthLong(fileLength);
            try (var is = new java.io.FileInputStream(videoFile); var os = response.getOutputStream()) {
                is.transferTo(os);
            }
            return;
        }

        String[] ranges = rangeHeader.substring(6).split("-");
        long start;
        long end;

        try {
            start = Long.parseLong(ranges[0]);
            end = (ranges.length > 1 && !ranges[1].isEmpty()) ? Long.parseLong(ranges[1]) : fileLength - 1;
        } catch (NumberFormatException e) {
            start = 0;
            end = fileLength - 1;
        }

        start = Math.max(0, start);
        end = Math.min(fileLength - 1, end);
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

        // 生成 M3U（绝对 URL）
        StringBuilder m3u = new StringBuilder("#EXTM3U\n");
        String seriesTitle = video.getSeriesName() != null ? video.getSeriesName() : video.getTitle();
        for (Video v : siblings) {
            String title = v.getTitle();
            if (v.getSeasonNumber() != null && v.getEpisodeNumber() != null) {
                title = String.format("S%02dE%02d - %s", v.getSeasonNumber(), v.getEpisodeNumber(), v.getTitle());
            }
            m3u.append("#EXTINF:-1,").append(title).append("\n");
            m3u.append(baseUrl).append("/api/v1/video/").append(v.getId()).append("/stream\n");
        }

        byte[] content = m3u.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/x-mpegurl; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + seriesTitle + ".m3u\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(new org.springframework.core.io.ByteArrayResource(content));
    }

    private String getServerBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
        // 如果请求来自 localhost/127.0.0.1，自动替换为局域网 IP
        String host = request.getServerName();
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            try {
                var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    var network = interfaces.nextElement();
                    if (network.isLoopback() || !network.isUp()) continue;
                    var addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        var addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            host = addr.getHostAddress();
                            break;
                        }
                    }
                    if (!"localhost".equals(host)) break;
                }
            } catch (Exception ignored) {}
        }
        return request.getScheme() + "://" + host + ":" + request.getServerPort();
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

    private VideoDTO toDTO(Video video, WatchProgress progress) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        boolean hasNfo = Files.exists(videoDir.resolve(baseName + ".nfo"));
        boolean hasPoster = Files.exists(videoDir.resolve(baseName + "-poster.jpg"));
        boolean hasFanart = Files.exists(videoDir.resolve(baseName + "-fanart.jpg"));
        boolean hasMetadataDir = Files.exists(nfoService.getMetadataDir(video));

        VideoDTO dto = VideoDTO.fromEntity(video, hasNfo, hasPoster, hasFanart, hasMetadataDir);

        if (progress != null) {
            dto.setWatchPosition(progress.getPositionSeconds());
            dto.setWatched(progress.getCompleted());
            if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
                dto.setWatchProgressPercent(progress.getPositionSeconds() / progress.getDurationSeconds() * 100);
            }
        }

        return dto;
    }

    private VideoDTO toDTO(Video video) {
        return toDTO(video, watchProgressService.getProgress(video.getId()));
    }
}
