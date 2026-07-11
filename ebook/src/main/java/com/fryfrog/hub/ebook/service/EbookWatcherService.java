package com.fryfrog.hub.ebook.service;

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
public class EbookWatcherService {

    private final EbookService metadataService;
    private final EbookMetadataScrapeService scrapeService;
    private final MediaLibraryService mediaLibraryService;
    private final PeriodicScanScheduler scanScheduler;

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Ebook watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            metadataService.fixMissingCoverPaths();
            metadataService.fixSeriesCoverPaths();
            List<String> rootPaths = getRootPaths();
            if (rootPaths.isEmpty()) return;
            for (String rootPath : rootPaths) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeAll();
            scrapeService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic ebook scan failed: {}", e.getMessage());
        }
    }

    private List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "EBOOK".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return metadataService.getRootPaths();
    }
}
