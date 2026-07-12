package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
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

    private volatile ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 完整流水线：扫描 → 刮削 → 整理 → 资产生成
     */
    public void runFullPipeline(String directoryPath, Long libraryId) {
        log.info("[Pipeline] Starting full pipeline for: {} (libraryId={})", directoryPath, libraryId);
        long startTime = System.currentTimeMillis();

        // Phase 1-2: 扫描 + 批量入库
        List<Video> videos = scanService.scanAndSave(directoryPath, libraryId);
        if (videos.isEmpty()) {
            log.info("[Pipeline] No videos found, pipeline complete");
            return;
        }

        // Phase 5-6: TMDB 刮削
        scrapeService.batchScrapeAndBind(videos);

        // Phase 7: 文件整理
        organizeService.batchOrganize(videos);

        // Phase 8: 资产生成
        assetService.batchGenerateAssets(videos);

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
