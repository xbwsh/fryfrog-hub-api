package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.video.model.Video;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoWatcherService {

    private final VideoService videoService;
    private final VideoScanService scanService;
    private final VideoScrapeService scrapeService;
    private final VideoOrganizeService organizeService;
    private final VideoAssetService assetService;
    private final PeriodicScanScheduler scanScheduler;
    private final MediaLibraryService mediaLibraryService;

    @Value("${hub.video.root-paths:}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.debug("[PeriodicScan] Video watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            log.debug("[PeriodicScan] Starting periodic scan...");
            List<String> rootPaths = getRootPaths();
            if (rootPaths.isEmpty()) {
                log.debug("[PeriodicScan] No root paths configured, skipping");
                return;
            }

            for (String rootPath : rootPaths) {
                // 查找对应的库 ID
                Long libraryId = null;
                var library = mediaLibraryService.findByPath(rootPath);
                if (library != null) {
                    libraryId = library.getId();
                }

                // Phase 1: 扫描 + 入库
                List<Video> videos = scanService.scanAndSave(rootPath, libraryId);
                if (videos.isEmpty()) continue;

                // Phase 2: TMDB 刮削
                scrapeService.batchScrapeAndBind(videos);

                // Phase 3: 文件整理
                organizeService.batchOrganize(videos);

                // Phase 4: 保存演员（整理后再保存，确保 actors 目录在正确位置）
                for (Video video : videos) {
                    if (video.getTmdbId() != null && video.getMediaType() != null) {
                        try {
                            assetService.saveActors(video, video.getMediaType(), video.getTmdbId(), null);
                        } catch (Exception e) {
                            log.debug("[PeriodicScan] Failed to save actors for {}: {}", video.getTitle(), e.getMessage());
                        }
                    }
                }

                // Phase 5: 资产生成（NFO + 封面）
                assetService.batchGenerateAssets(videos);
            }

            log.debug("[PeriodicScan] Periodic scan completed");
        } catch (Exception e) {
            log.warn("[PeriodicScan] Periodic video scan failed: {}", e.getMessage());
        }
    }
}
