package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookWatcherService {

    private final EbookService metadataService;
    private final EbookMetadataScrapeService scrapeService;
    private final PeriodicScanScheduler scanScheduler;

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Ebook watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            for (String rootPath : metadataService.getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeAll();
            scrapeService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic ebook scan failed: {}", e.getMessage());
        }
    }
}
