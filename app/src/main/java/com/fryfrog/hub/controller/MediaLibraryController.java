package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.comic.service.ComicMetadataService;
import com.fryfrog.hub.ebook.service.EbookService;
import com.fryfrog.hub.music.service.MusicMetadataService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/media-libraries")
@Tag(name = "媒体资源库管理", description = "资源库 CRUD + 扫描 + 目录浏览")
public class MediaLibraryController {

    private static final Logger log = LoggerFactory.getLogger(MediaLibraryController.class);

    private final MediaLibraryService service;
    private final VideoService videoService;
    private final MusicMetadataService musicMetadataService;
    private final ComicMetadataService comicMetadataService;
    private final EbookService ebookService;

    public MediaLibraryController(MediaLibraryService service,
                                  VideoService videoService, MusicMetadataService musicMetadataService,
                                  ComicMetadataService comicMetadataService, EbookService ebookService) {
        this.service = service;
        this.videoService = videoService;
        this.musicMetadataService = musicMetadataService;
        this.comicMetadataService = comicMetadataService;
        this.ebookService = ebookService;
    }

    // ── CRUD ──

    @GetMapping
    @Operation(summary = "获取所有资源库")
    public ResponseEntity<ApiResponse<List<MediaLibrary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllLibraries()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取资源库详情")
    public ResponseEntity<ApiResponse<MediaLibrary>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getLibraryById(id)));
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
        return ResponseEntity.ok(ApiResponse.success(service.updateLibrary(id, library)));
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
    @Operation(summary = "扫描所有启用的资源库", description = "按类型分发到对应模块扫描（异步执行）")
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
    @Operation(summary = "扫描指定资源库", description = "根据资源库类型分发到对应模块扫描（异步执行）")
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

    private void scanLibrary(MediaLibrary library, Map<String, String> scanResult) {
        String key = library.getType().toLowerCase() + ":" + library.getId();
        try {
            switch (library.getType().toUpperCase()) {
                case "VIDEO" -> videoService.scanDirectory(library.getPath(), library.getId());
                case "MUSIC" -> musicMetadataService.scanDirectory(library.getPath());
                case "COMIC" -> comicMetadataService.scanDirectory(library.getPath());
                case "EBOOK" -> {
                    ebookService.scanDirectory(library.getPath());
                    ebookService.organizeAll();
                }
                default -> {
                    scanResult.put(key, "skip: unknown type " + library.getType());
                    return;
                }
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
        if (path == null || path.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(listRoots()));
        }
        Path dirPath = Paths.get(path);
        if (!Files.isDirectory(dirPath)) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(listChildren(dirPath.toFile())));
    }

    private List<Map<String, Object>> listRoots() {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> added = new HashSet<>();

        // Docker 环境优先暴露媒体挂载路径
        String[] mediaPaths = {
                "/data/media/music", "/data/media/video", "/data/media/comic", "/data/media/ebook",
                "/data/media", "/data"
        };
        for (String p : mediaPaths) {
            File dir = new File(p);
            if (dir.exists() && dir.isDirectory() && added.add(dir.getAbsolutePath())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", p);
                item.put("path", dir.getAbsolutePath());
                item.put("writable", dir.canWrite());
                result.add(item);
            }
        }

        // 补充系统根目录（Linux 下 / 通常对用户无意义，但 Windows 盘符有用）
        for (File root : File.listRoots()) {
            String rootPath = root.getAbsolutePath();
            if (added.add(rootPath)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", rootPath);
                item.put("path", rootPath);
                item.put("writable", root.canWrite());
                result.add(item);
            }
        }

        return result;
    }

    private List<Map<String, Object>> listChildren(File dir) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath(), Files::isDirectory)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("path", child.toAbsolutePath().toString());
                item.put("writable", Files.isWritable(child));
                result.add(item);
            }
        } catch (IOException e) {
            log.warn("Failed to list directory: {}", dir.getAbsolutePath(), e);
            return List.of();
        }
        result.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
        return result;
    }
}
