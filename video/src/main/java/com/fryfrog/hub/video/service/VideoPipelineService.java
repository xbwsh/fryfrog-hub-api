package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频流水线服务：编排扫描、刮削、整理、资产生成的完整流程。
 * 每个阶段独立执行，阶段间通过数据传递，避免重复 I/O。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPipelineService {

    private final VideoScanService scanService;
    private final VideoScrapeService scrapeService;
    private final VideoOrganizeService organizeService;
    private final VideoAssetService assetService;
    private final VideoRepository videoRepository;
    private final MediaLibraryService mediaLibraryService;

    private volatile ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 检查媒体库是否启用刮削整理
     */
    private boolean isScrapingEnabled(Long libraryId) {
        if (libraryId == null) return true;
        try {
            var library = mediaLibraryService.getLibraryById(libraryId);
            Boolean enabled = library.getEnableScraping();
            return enabled == null || enabled;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 完整流水线：扫描 → 刮削 → 整理 → 资产生成
     */
    public void runFullPipeline(String directoryPath, Long libraryId) {
        log.info("[Pipeline] Starting full pipeline for: {} (libraryId={})", directoryPath, libraryId);
        long startTime = System.currentTimeMillis();

        // Phase 1-2: 扫描 + 批量入库（始终执行）
        List<Video> videos = scanService.scanAndSave(directoryPath, libraryId);
        if (videos.isEmpty()) {
            log.info("[Pipeline] No videos found, pipeline complete");
            return;
        }

        // 检查媒体库是否启用刮削整理
        boolean scrapingEnabled = isScrapingEnabled(libraryId);
        if (!scrapingEnabled) {
            log.info("[Pipeline] Scraping/organizing disabled for library {}, scan-only mode", libraryId);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Pipeline] Pipeline complete (scan-only): {} videos in {}ms", videos.size(), elapsed);
            return;
        }

        // Phase 3: TMDB 刮削
        scrapeService.batchScrapeAndBind(videos);

        // 重新获取视频列表（刮削后 tmdbId 等字段已更新）
        List<Long> videoIds = videos.stream().map(Video::getId).toList();
        List<Video> scrapedVideos = videoRepository.findAllById(videoIds);
        if (!scrapedVideos.isEmpty()) {
            videos = scrapedVideos;
        }

        // Phase 4: 保存演员
        for (Video video : videos) {
            if (video.getTmdbId() != null && video.getMediaType() != null) {
                try {
                    assetService.saveActors(video, video.getMediaType(), video.getTmdbId(), null);
                } catch (Exception e) {
                    log.debug("[Pipeline] Failed to save actors for {}: {}", video.getTitle(), e.getMessage());
                }
            }
        }

        // Phase 5: 文件整理
        organizeService.batchOrganize(videos);

        // Phase 6: 资产生成
        assetService.batchGenerateAssets(videos);

        // Phase 7: 清理空目录
        try {
            organizeService.cleanupEmptyLibraryDir(directoryPath);
        } catch (Exception e) {
            log.debug("[Pipeline] Failed to cleanup empty dirs: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Pipeline] Full pipeline complete: {} videos in {}ms", videos.size(), elapsed);
    }

    /**
     * 完整流水线（异步）
     */
    public void runFullPipelineAsync(String directoryPath, Long libraryId) {
        pipelineExecutor.submit(() -> runFullPipeline(directoryPath, libraryId));
    }

    /**
     * 仅扫描（不刮削、不整理）
     */
    public List<Video> runScanOnly(String directoryPath, Long libraryId) {
        log.info("[Pipeline] Running scan-only for: {} (libraryId={})", directoryPath, libraryId);
        return scanService.scanAndSave(directoryPath, libraryId);
    }

    /**
     * 仅刮削（不扫描、不整理）
     */
    public void runScrapeOnly(Long libraryId) {
        log.info("[Pipeline] Running scrape-only (libraryId={})", libraryId);
        List<Video> videos = scrapeService.findUnscraped(libraryId);
        if (!videos.isEmpty()) {
            scrapeService.batchScrapeAndBind(videos);
            assetService.batchGenerateAssets(videos);
        }
    }

    /**
     * 仅刮削（异步）
     */
    public void runScrapeOnlyAsync(Long libraryId) {
        pipelineExecutor.submit(() -> runScrapeOnly(libraryId));
    }

    /**
     * 仅整理（不扫描、不刮削）
     */
    public Map<String, Object> runOrganizeOnly(String path) {
        log.info("[Pipeline] Running organize-only for path: {}", path);
        List<Video> videos = scanService.findByPath(path);
        return organizeService.batchOrganize(videos);
    }

    /**
     * 重新刮削指定库（解绑 → 扫描 → 刮削）
     */
    public void runRescrapeLibrary(Long libraryId) {
        log.info("[Pipeline] Running rescrape for library: {}", libraryId);
        scrapeService.rescrapeByLibrary(libraryId);
    }

    /**
     * 重新刮削指定库（异步）
     */
    public void runRescrapeLibraryAsync(Long libraryId) {
        pipelineExecutor.submit(() -> runRescrapeLibrary(libraryId));
    }

    /**
     * 重新刮削单个视频所属系列
     */
    public List<Video> runRescrapeVideo(Long videoId) {
        log.info("[Pipeline] Running rescrape for video: {}", videoId);
        return scrapeService.rescrapeVideo(videoId);
    }

    /**
     * 生成单个视频的资产
     */
    public void runAssetGeneration(Long videoId) {
        log.info("[Pipeline] Running asset generation for video: {}", videoId);
        // This would need to fetch the video first
        // For now, delegate to the service
    }
}
