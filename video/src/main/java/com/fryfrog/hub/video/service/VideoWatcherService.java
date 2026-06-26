package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.common.service.SystemSettingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoWatcherService {

    private final VideoService metadataService;
    private final SystemSettingService settingService;
    private final PeriodicScanScheduler scanScheduler;

    @Value("${hub.video.root-paths:./media-library/video}")
    private String rootPathsConfig;

    @Value("${hub.video.supported-formats}")
    private String supportedFormats;

    private final List<WatchService> watchServices = new ArrayList<>();
    private ExecutorService executor;
    private volatile boolean running = true;

    private final Set<String> scrapingPaths = ConcurrentHashMap.newKeySet();
    private Set<String> supportedFormatSet;

    public void addScrapingPath(String path) {
        scrapingPaths.add(path);
    }

    public void removeScrapingPath(String path) {
        scrapingPaths.remove(path);
    }

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void startWatching() {
        supportedFormatSet = Set.of(supportedFormats.split(","));
        List<String> rootPaths = getRootPaths();

        for (String rootPath : rootPaths) {
            Path dir = Paths.get(rootPath);
            if (!Files.isDirectory(dir)) {
                log.warn("Video directory does not exist: {}", rootPath);
                continue;
            }

            try {
                WatchService ws = FileSystems.getDefault().newWatchService();
                watchServices.add(ws);
                registerDirectory(dir, ws);
                log.info("Started watching video directory: {}", rootPath);
            } catch (IOException e) {
                log.error("Failed to start video watcher for {}", rootPath, e);
            }
        }

        if (!watchServices.isEmpty()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "video-watcher");
                t.setDaemon(true);
                return t;
            });
            executor.execute(this::watch);
            scanScheduler.registerTask(this::periodicScan);
            log.info("Video watcher registered, watching {} directories", watchServices.size());
        }
    }

    private void periodicScan() {
        try {
            for (String rootPath : getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeVideos(null);
            metadataService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic video scan failed: {}", e.getMessage());
        }
    }

    private void registerDirectory(Path dir, WatchService ws) throws IOException {
        dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(subDir -> {
                try {
                    registerDirectory(subDir, ws);
                } catch (IOException e) {
                    log.warn("Failed to register subdirectory: {}", subDir, e);
                }
            });
        }
    }

    private void watch() {
        while (running) {
            for (WatchService ws : watchServices) {
                try {
                    WatchKey key = ws.poll();
                    if (key == null) continue;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();
                        Path parentDir = (Path) key.watchable();
                        Path fullPath = parentDir.resolve(fileName);

                        if (scrapingPaths.contains(fullPath.toString())) {
                            log.debug("Skipping file being scraped: {}", fullPath);
                            continue;
                        }

                        if (Files.isDirectory(fullPath)) {
                            try {
                                registerDirectory(fullPath, ws);
                                log.debug("Registered new subdirectory: {}", fullPath);
                            } catch (IOException e) {
                                log.warn("Failed to register new subdirectory: {}", fullPath, e);
                            }
                            continue;
                        }

                        if (Files.isRegularFile(fullPath) && isSupportedFormat(fileName.toString())) {
                            log.debug("Detected video file change: {}", fullPath);
                            executor.submit(() -> {
                                try {
                                    Thread.sleep(3000);
                                    if (Files.exists(fullPath) && Files.size(fullPath) > 0) {
                                        metadataService.extractAndSaveMetadata(fullPath.toString());
                                        log.debug("Auto-indexed video: {}", fileName);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to auto-index video: {}", fileName, e);
                                }
                            });
                        }
                    }
                    key.reset();
                } catch (Exception e) {
                    log.error("Error in video watcher", e);
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean isSupportedFormat(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return supportedFormatSet.contains(ext);
    }

    @PreDestroy
    public void stopWatching() {
        running = false;
        for (WatchService ws : watchServices) {
            try {
                ws.close();
            } catch (IOException e) {
                log.error("Failed to close watch service", e);
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
        log.info("Stopped watching video directories");
    }
}
