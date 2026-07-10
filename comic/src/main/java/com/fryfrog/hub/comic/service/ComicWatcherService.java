package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicWatcherService {

    private final ComicMetadataService metadataService;
    private final MangaScrapeService mangaScrapeService;
    private final PeriodicScanScheduler scanScheduler;

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Comic watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            for (String rootPath : metadataService.getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeAll();
            mangaScrapeService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic comic scan failed: {}", e.getMessage());
        }
    }
}
