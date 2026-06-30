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
    @Operation(summary = "扫描所有启用的资源库", description = "按类型分发到对应模块扫描，完成后整理视频文件")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanAll() {
        log.info("Starting full library scan...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> scanResult = new LinkedHashMap<>();

        for (MediaLibrary library : service.getEnabledLibraries()) {
            scanLibrary(library, scanResult);
        }
        result.put("scan", scanResult);

        try {
            result.put("organize", videoService.organizeVideos(null));
        } catch (Exception e) {
            result.put("organize", Map.of("error", e.getMessage()));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        result.put("elapsedMs", elapsed);

        log.info("Full library scan completed in {}ms", elapsed);
        return ResponseEntity.ok(ApiResponse.success("资源库扫描完成", result));
    }

    @PostMapping("/{id}/scan")
    @Operation(summary = "扫描指定资源库", description = "根据资源库类型分发到对应模块扫描")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanById(
            @Parameter(description = "资源库ID") @PathVariable Long id) {
        MediaLibrary library = service.getLibraryById(id);
        long startTime = System.currentTimeMillis();

        Map<String, String> scanResult = new LinkedHashMap<>();
        scanLibrary(library, scanResult);

        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scan", scanResult);
        result.put("elapsedMs", elapsed);

        return ResponseEntity.ok(ApiResponse.success("资源库扫描完成", result));
    }

    private void scanLibrary(MediaLibrary library, Map<String, String> scanResult) {
        String key = library.getType().toLowerCase() + ":" + library.getId();
        try {
            switch (library.getType().toUpperCase()) {
                case "VIDEO" -> videoService.scanDirectory(library.getPath(), library.getId());
                case "MUSIC" -> musicMetadataService.scanDirectory(library.getPath());
                case "COMIC" -> comicMetadataService.scanDirectory(library.getPath());
                case "EBOOK" -> ebookService.scanDirectory(library.getPath());
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
    @Operation(summary = "浏览服务器目录", description = "列出指定路径下的子目录，用于前端目录选择器")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> browse(
            @Parameter(description = "目录路径，不传则从用户主目录开始浏览")
            @RequestParam(required = false) String path) {
        File dir;
        if (path == null || path.isBlank()) {
            File home = new File(System.getProperty("user.home"));
            if (home.exists() && home.isDirectory()) {
                return ResponseEntity.ok(ApiResponse.success(listChildren(home)));
            }
            File[] roots = File.listRoots();
            List<Map<String, Object>> result = new ArrayList<>();
            for (File root : roots) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", root.getAbsolutePath());
                item.put("path", root.getAbsolutePath());
                item.put("writable", root.canWrite());
                result.add(item);
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(listChildren(dir)));
    }

    private List<Map<String, Object>> listChildren(File dir) {
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (File child : children) {
            if (child.isHidden()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", child.getName());
            item.put("path", child.getAbsolutePath());
            item.put("writable", child.canWrite());
            result.add(item);
        }
        result.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
        return result;
    }
}
