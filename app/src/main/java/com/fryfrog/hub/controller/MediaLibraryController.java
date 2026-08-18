package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.dto.PipelineProgressDTO;
import com.fryfrog.hub.common.dto.ScrapeProgress;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.music.service.MusicScanService;
import com.fryfrog.hub.video.service.MediaLibraryBrowseService;
import com.fryfrog.hub.video.service.VideoPipelineService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/media-libraries")
@Tag(name = "媒体资源库管理", description = "资源库 CRUD + 扫描 + 目录浏览")
public class MediaLibraryController {

    private static final Logger log = LoggerFactory.getLogger(MediaLibraryController.class);

    private final MediaLibraryService service;
    private final VideoService videoService;
    private final ScrapeProgressService progressService;
    private final VideoPipelineService pipelineService;
    private final MediaLibraryBrowseService browseService;
    private final MusicScanService musicScanService;

    public MediaLibraryController(MediaLibraryService service, VideoService videoService, ScrapeProgressService progressService, VideoPipelineService pipelineService, MediaLibraryBrowseService browseService, MusicScanService musicScanService) {
        this.service = service;
        this.videoService = videoService;
        this.progressService = progressService;
        this.pipelineService = pipelineService;
        this.browseService = browseService;
        this.musicScanService = musicScanService;
    }

    // ── CRUD ──

    @GetMapping
    @Operation(summary = "获取所有资源库", description = "管理员可见全部；普通用户仅可见被分配的启用库")
    public ResponseEntity<ApiResponse<List<MediaLibrary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getVisibleLibraries()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取资源库详情")
    public ResponseEntity<ApiResponse<MediaLibrary>> getById(@PathVariable Long id) {
        MediaLibrary library = service.getLibraryById(id);
        if (!service.isVisibleToCurrentUser(id)) {
            throw new com.fryfrog.hub.common.exception.ResourceNotFoundException("MediaLibrary", "id", id);
        }
        return ResponseEntity.ok(ApiResponse.success(library));
    }

    @PostMapping
    @Operation(summary = "创建资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> create(@RequestBody MediaLibrary library) {
        return ResponseEntity.ok(ApiResponse.success(service.createLibrary(library)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> update(
            @PathVariable Long id,
            @RequestBody MediaLibrary library) {
        MediaLibrary existing = service.getLibraryById(id);
        boolean oldAdult = Boolean.TRUE.equals(existing.getIsAdult());
        MediaLibrary saved = service.updateLibrary(id, library);
        boolean newAdult = Boolean.TRUE.equals(saved.getIsAdult());

        // 成人标记变化时，异步同步库内视频及其所属系列
        if (oldAdult != newAdult) {
            log.info("Library '{}' isAdult changed: {} -> {}, syncing videos/series", saved.getName(), oldAdult, newAdult);
            Thread.startVirtualThread(() -> videoService.syncAdultByLibrary(id, newAdult));
        }

        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源库")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        service.deleteLibrary(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", id)));
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "启用/禁用资源库")
    public ResponseEntity<ApiResponse<MediaLibrary>> toggle(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.toggleLibrary(id)));
    }

    // ── 扫描 ──

    @PostMapping("/scan")
    @Operation(summary = "扫描所有启用的资源库", description = "扫描视频资源库（异步执行）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanAll() {
        log.info("Starting full library scan...");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", service.getEnabledLibraries().size());

        // 异步执行扫描，避免前端超时
        Thread.startVirtualThread(() -> {
            try {
                Map<String, String> scanResult = new LinkedHashMap<>();
                for (MediaLibrary library : service.getEnabledLibraries()) {
                    scanLibrary(library, scanResult);
                }
                log.info("Full library scan completed: {}", scanResult);
            } catch (Exception e) {
                log.error("Full library scan failed: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    @PostMapping("/{id}/scan")
    @Operation(summary = "扫描指定资源库", description = "根据资源库类型扫描视频资源（异步执行）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanById(
            @Parameter(description = "资源库ID") @PathVariable Long id) {
        MediaLibrary library = service.getLibraryById(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("libraryId", id);
        result.put("libraryName", library.getName());
        result.put("status", "started");

        // 异步执行扫描，避免前端超时
        log.info("[Scan] Starting async scan for library '{}' (type={}, path={})", library.getName(), library.getType(), library.getPath());
        Thread.startVirtualThread(() -> {
            log.info("[Scan] Virtual thread started for library '{}'", library.getName());
            try {
                Map<String, String> scanResult = new LinkedHashMap<>();
                scanLibrary(library, scanResult);
                log.info("[Scan] Async scan completed for library '{}': {}", library.getName(), scanResult);
            } catch (Exception e) {
                log.error("[Scan] Async scan failed for library '{}': {}", library.getName(), e.getMessage(), e);
            }
        });

        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    @GetMapping("/scan/progress")
    @Operation(summary = "获取扫描进度", description = "返回指定资源库的扫描进度，不传 libraryId 时返回全部资源库的进度")
    public ResponseEntity<ApiResponse<List<ScrapeProgress>>> getScanProgress(
            @Parameter(description = "资源库ID，可选") @RequestParam(required = false) Long libraryId) {
        if (libraryId != null) {
            return ResponseEntity.ok(ApiResponse.success(List.of(progressService.getProgress("scan:" + libraryId))));
        }
        List<ScrapeProgress> progressList = service.getEnabledLibraries().stream()
                .map(lib -> progressService.getProgress("scan:" + lib.getId()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(progressList));
    }

    @GetMapping("/{id}/pipeline-progress")
    @Operation(summary = "获取流水线聚合进度", description = "返回指定资源库扫描+刮削+资产生成的整体进度，前端进度条用")
    public ResponseEntity<ApiResponse<PipelineProgressDTO>> getPipelineProgress(
            @Parameter(description = "资源库ID") @PathVariable Long id) {
        MediaLibrary library = service.getLibraryById(id);
        boolean scrapingEnabled = library.getEnableScraping() == null || library.getEnableScraping();

        ScrapeProgress pipeline = progressService.getProgress(VideoPipelineService.progressModule(id));
        ScrapeProgress scan = progressService.getProgress("scan:" + id);
        ScrapeProgress scrape = progressService.getProgress("video");

        return ResponseEntity.ok(ApiResponse.success(
                PipelineProgressDTO.of(id, pipeline, scan, scrape, scrapingEnabled)));
    }

    private void scanLibrary(MediaLibrary library, Map<String, String> scanResult) {
        String key = library.getType().toLowerCase() + ":" + library.getId();
        try {
            if ("VIDEO".equalsIgnoreCase(library.getType())) {
                // 完整流水线：扫描 → 刮削 → 整理 → 资产生成（enableScraping=false 时自动降级为仅扫描）
                pipelineService.runFullPipeline(library.getPath(), library.getId());
            } else if (library.isMusicType()) {
                // 音乐：扫描 + ffprobe 标签建库
                musicScanService.scanAndSave(library.getPath(), library.getId());
            } else {
                scanResult.put(key, "skip: unsupported type " + library.getType());
                return;
            }
            scanResult.put(key, "ok");
            log.info("Scanned library '{}' ({}): {}", library.getName(), library.getType(), library.getPath());
        } catch (Exception e) {
            scanResult.put(key, "error: " + e.getMessage());
            log.error("Failed to scan library '{}' ({}): {}", library.getName(), library.getType(), library.getPath(), e);
        }
    }

    // ── 目录浏览 ──

    @GetMapping("/browse")
    @Operation(summary = "浏览服务器目录", description = "列出指定路径下的子目录，用于前端目录选择器。不传 path 时返回磁盘根目录列表")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> browse(
            @Parameter(description = "目录路径，不传则列出所有磁盘根目录")
            @RequestParam(required = false) String path) {
        return ResponseEntity.ok(ApiResponse.success(browseService.listChildren(path)));
    }
}
