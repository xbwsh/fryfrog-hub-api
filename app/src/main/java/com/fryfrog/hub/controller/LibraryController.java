package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.service.MusicMetadataService;
import com.fryfrog.hub.comic.service.ComicMetadataService;
import com.fryfrog.hub.ebook.service.EbookService;
import com.fryfrog.hub.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/library")
@Tag(name = "资源库管理", description = "一键整理资源库，清理无效记录并重新扫描")
public class LibraryController {

    private static final Logger log = LoggerFactory.getLogger(LibraryController.class);

    private final MusicMetadataService musicService;
    private final ComicMetadataService comicService;
    private final EbookService ebookService;
    private final VideoService videoService;

    public LibraryController(MusicMetadataService musicService, ComicMetadataService comicService,
                             EbookService ebookService, VideoService videoService) {
        this.musicService = musicService;
        this.comicService = comicService;
        this.ebookService = ebookService;
        this.videoService = videoService;
    }

    @PostMapping("/rescan")
    @Operation(summary = "一键整理资源库", description = "清理所有模块中文件已不存在的无效记录，然后重新扫描所有媒体目录")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rescanAll() {
        log.info("Starting full library rescan...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Integer> cleanupResult = new LinkedHashMap<>();
        cleanupResult.put("music", musicService.cleanupInvalidRecords());
        cleanupResult.put("comic", comicService.cleanupInvalidRecords());
        cleanupResult.put("ebook", ebookService.cleanupInvalidRecords());
        cleanupResult.put("video", videoService.cleanupInvalidRecords());
        result.put("cleanup", cleanupResult);

        Map<String, String> scanResult = new LinkedHashMap<>();
        try { musicService.scanFromRoot(); scanResult.put("music", "ok"); } catch (Exception e) { scanResult.put("music", "error: " + e.getMessage()); }
        try { comicService.scanFromRoot(); scanResult.put("comic", "ok"); } catch (Exception e) { scanResult.put("comic", "error: " + e.getMessage()); }
        try { ebookService.scanFromRoot(); scanResult.put("ebook", "ok"); } catch (Exception e) { scanResult.put("ebook", "error: " + e.getMessage()); }
        try { videoService.scanDirectory(videoService.getRootPath()); scanResult.put("video", "ok"); } catch (Exception e) { scanResult.put("video", "error: " + e.getMessage()); }
        result.put("scan", scanResult);

        long elapsed = System.currentTimeMillis() - startTime;
        result.put("elapsedMs", elapsed);

        log.info("Full library rescan completed in {}ms", elapsed);
        return ResponseEntity.ok(ApiResponse.success("资源库整理完成", result));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "仅清理无效记录", description = "删除所有模块中文件已不存在的数据库记录，不重新扫描")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> cleanupAll() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("music", musicService.cleanupInvalidRecords());
        result.put("comic", comicService.cleanupInvalidRecords());
        result.put("ebook", ebookService.cleanupInvalidRecords());
        result.put("video", videoService.cleanupInvalidRecords());
        return ResponseEntity.ok(ApiResponse.success("清理完成", result));
    }
}
