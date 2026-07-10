package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
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
    private final PeriodicScanScheduler scanScheduler;
    private final MediaLibraryService mediaLibraryService;

    @Value("${hub.video.root-paths:./media-library/video}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void init() {
        scanScheduler.registerTask(this::periodicScan);
        log.info("Video watcher initialized (polling mode)");
    }

    private void periodicScan() {
        try {
            List<MediaLibrary> libraries = mediaLibraryService.getEnabledLibraries();
            if (!libraries.isEmpty()) {
                for (MediaLibrary library : libraries) {
                    videoService.scanDirectory(library.getPath(), library.getId());
                }
            } else {
                for (String rootPath : getRootPaths()) {
                    videoService.scanDirectory(rootPath);
                }
            }
            videoService.organizeVideos(null);
            videoService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic video scan failed: {}", e.getMessage());
        }
    }
}
