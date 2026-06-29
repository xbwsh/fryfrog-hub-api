package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.comic.service.ComicMetadataService;
import com.fryfrog.hub.ebook.service.EbookService;
import com.fryfrog.hub.music.service.MusicMetadataService;
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

    private final VideoService videoService;
    private final MusicMetadataService musicMetadataService;
    private final ComicMetadataService comicMetadataService;
    private final EbookService ebookService;

    public LibraryController(VideoService videoService, MusicMetadataService musicMetadataService,
                             ComicMetadataService comicMetadataService, EbookService ebookService) {
        this.videoService = videoService;
        this.musicMetadataService = musicMetadataService;
        this.comicMetadataService = comicMetadataService;
        this.ebookService = ebookService;
    }

    @PostMapping("/rescan")
    @Operation(summary = "一键整理资源库", description = "清理所有模块中文件已不存在的无效记录，然后重新扫描所有媒体目录")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rescanAll() {
        log.info("Starting full library rescan...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, String> scanResult = new LinkedHashMap<>();
        try { videoService.scanDirectory(videoService.getRootPath()); scanResult.put("video", "ok"); } catch (Exception e) { scanResult.put("video", "error: " + e.getMessage()); }
        try { musicMetadataService.scanDirectory(musicMetadataService.getFirstRootPath()); scanResult.put("music", "ok"); } catch (Exception e) { scanResult.put("music", "error: " + e.getMessage()); }
        try { comicMetadataService.scanDirectory(comicMetadataService.getFirstRootPath()); scanResult.put("comic", "ok"); } catch (Exception e) { scanResult.put("comic", "error: " + e.getMessage()); }
        try { ebookService.scanDirectory(ebookService.getFirstRootPath()); scanResult.put("ebook", "ok"); } catch (Exception e) { scanResult.put("ebook", "error: " + e.getMessage()); }
        result.put("scan", scanResult);

        // 整理视频文件到正确目录
        try {
            Map<String, Object> organizeResult = videoService.organizeVideos(null);
            result.put("organize", organizeResult);
        } catch (Exception e) {
            result.put("organize", Map.of("error", e.getMessage()));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        result.put("elapsedMs", elapsed);

        log.info("Full library rescan completed in {}ms", elapsed);
        return ResponseEntity.ok(ApiResponse.success("资源库整理完成", result));
    }
}
