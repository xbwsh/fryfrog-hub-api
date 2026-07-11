package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicWatcherService {

    private final ComicMetadataService metadataService;
    private final MangaScrapeService mangaScrapeService;
    private final MediaLibraryService mediaLibraryService;
    private final PeriodicScanScheduler scanScheduler;

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Comic watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            List<String> rootPaths = getRootPaths();
            if (rootPaths.isEmpty()) return;
            for (String rootPath : rootPaths) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeAll();
            mangaScrapeService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic comic scan failed: {}", e.getMessage());
        }
    }

    private List<String> getRootPaths() {
        // 优先从数据库 MediaLibrary 读取（前端配置）
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "COMIC".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        // 回退到配置文件
        return metadataService.getRootPaths();
    }
}
