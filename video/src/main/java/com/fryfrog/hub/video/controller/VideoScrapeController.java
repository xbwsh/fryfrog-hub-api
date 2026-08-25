package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.video.dto.TmdbSearchResult;
import com.fryfrog.hub.video.dto.VideoBindRequest;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.service.CoverArtService;
import com.fryfrog.hub.video.service.MediaProbeService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.SeriesService;
import com.fryfrog.hub.video.service.VideoAssetService;
import com.fryfrog.hub.video.service.VideoOrganizeService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "视频刮削管理", description = "TMDB 绑定、批量刮削任务与进度接口")
public class VideoScrapeController {

    private final VideoService service;
    private final NfoService nfoService;
    private final CoverArtService coverArtService;
    private final MediaProbeService probeService;
    private final VideoRepository videoRepository;
    private final ScrapeProgressService scrapeProgressService;
    private final VideoOrganizeService organizeService;
    private final VideoAssetService assetService;
    private final SeriesService seriesService;
    private final PeriodicScanScheduler scanScheduler;
    private final MediaLibraryService mediaLibraryService;
    private final VideoControllerSupport support;

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
        Video videoForCheck = service.getVideoById(id);
        if (!mediaLibraryService.isVisibleToCurrentUser(videoForCheck.getLibraryId())) {
            throw new ResourceNotFoundException("Video", "id", id);
        }
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
        if (!mediaLibraryService.isVisibleToCurrentUser(video.getLibraryId())) {
            throw new ResourceNotFoundException("Video", "id", id);
        }
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
        Video videoForCheck = service.getVideoById(id);
        support.requireLibraryVisible(videoForCheck.getLibraryId(), id, "Video");
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
    @Operation(summary = "按资源库重新刮削", description = "解绑指定资源库中所有视频的TMDB绑定，然后根据资源库类型重新搜索绑定（异步执行，进度见 scrape/progress?module=video）")
    public ResponseEntity<ApiResponse<String>> rescrapeByLibrary(
            @Parameter(description = "资源库ID") @PathVariable Long libraryId, HttpServletRequest request) {
        support.requireAdmin(request);
        support.requireLibraryVisible(mediaLibraryService.getLibraryById(libraryId).getId(), libraryId, "MediaLibrary");

        // 暂停 periodic-scan 防止并发冲突；整库解绑+扫描+刮削耗时长，必须异步避免 HTTP 超时
        scanScheduler.setBusy(true);
        Thread.startVirtualThread(() -> {
            try {
                service.rescrapeByLibrary(libraryId);
                log.info("[Rescrape] Library {} rescrape completed", libraryId);
            } catch (Exception e) {
                log.error("[Rescrape] Library {} rescrape failed: {}", libraryId, e.getMessage(), e);
            } finally {
                scanScheduler.setBusy(false);
            }
        });

        return ResponseEntity.ok(ApiResponse.success("Rescrape started for library " + libraryId));
    }

    @PostMapping("/refresh-all-actors")
    @Operation(summary = "批量刷新演员", description = "异步刷新所有开启刮削媒体库中已绑定 TMDB 的视频的演员信息（含电影和剧集），进度查询见 scrape/progress?module=actors")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllActors(HttpServletRequest request) {
        support.requireAdmin(request);
        // 获取启用刮削的媒体库 ID
        List<Long> scrapeEnabledLibraryIds = mediaLibraryService.getVisibleLibraries().stream()
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
            @Parameter(description = "视频ID") @PathVariable Long id, HttpServletRequest request) {
        support.requireAdmin(request);
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAllLogos(HttpServletRequest request) {
        support.requireAdmin(request);
        // 获取启用刮削的媒体库 ID
        List<Long> scrapeEnabledLibraryIds = mediaLibraryService.getVisibleLibraries().stream()
                .filter(lib -> Boolean.TRUE.equals(lib.getEnableScraping()))
                .map(lib -> lib.getId())
                .toList();

        // 开启刮削媒体库中已绑定 TMDB 的系列
        List<VideoSeries> seriesWithTmdb = seriesService.getAllSeries().stream()
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
            for (VideoSeries series : seriesWithTmdb) {
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
                    String resolution = probeService.probeResolution(video.getFilePath());
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
            @Parameter(description = "视频ID") @PathVariable Long id, HttpServletRequest request) {
        support.requireAdmin(request);
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        String nfoPath = nfoService.generateNfo(video);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "nfoPath", nfoPath != null ? nfoPath : "null"
        )));
    }

    @PostMapping("/{id:\\d+}/covers")
    @Operation(summary = "下载封面图片", description = "下载视频的竖屏海报和横屏背景图")
    public ResponseEntity<ApiResponse<Map<String, String>>> downloadCovers(
            @Parameter(description = "视频ID") @PathVariable Long id, HttpServletRequest request) {
        support.requireAdmin(request);
        Video video = service.getVideoById(id);
        support.requireLibraryVisible(video.getLibraryId(), id, "Video");
        boolean success = coverArtService.downloadAllCovers(video, true);
        if (success) {
            videoRepository.save(video);
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "videoId", String.valueOf(id),
                "success", String.valueOf(success)
        )));
    }
}
