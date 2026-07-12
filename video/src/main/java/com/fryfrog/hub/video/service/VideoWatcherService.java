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

    @Value("${hub.video.root-paths:}")
    private String rootPathsConfig;

    private List<String> getRootPaths() {
        // 优先从数据库 MediaLibrary 读取（前端配置），只取 VIDEO 类型
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        // 回退到配置文件
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
            List<String> rootPaths = getRootPaths();
            if (rootPaths.isEmpty()) return;
            for (String rootPath : rootPaths) {
                videoService.scanDirectory(rootPath);
            }
            videoService.organizeVideos(null);
            videoService.autoScrapeAll(false);
        } catch (Exception e) {
            log.warn("Periodic video scan failed: {}", e.getMessage());
        }
    }
}
