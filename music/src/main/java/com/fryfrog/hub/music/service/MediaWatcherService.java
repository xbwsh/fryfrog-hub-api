package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaWatcherService {

    private final MusicMetadataService metadataService;
    private final MusicScrapeService scrapeService;
    private final PeriodicScanScheduler scanScheduler;

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Music watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            for (String rootPath : metadataService.getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
            var tracks = metadataService.getAllTracks();
            for (var track : tracks) {
                if (scrapeService.needsScraping(track)) {
                    scrapeService.scrapeTrack(track);
                }
            }
        } catch (Exception e) {
            log.warn("Periodic music scan failed: {}", e.getMessage());
        }
    }
}
