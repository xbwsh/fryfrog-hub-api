package com.fryfrog.hub.music.service;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaWatcherService {

    private final MusicMetadataService metadataService;
    private final SystemSettingService settingService;

    @Value("${hub.music.root-paths:./media-library/music}")
    private String rootPathsConfig;

    @Value("${hub.music.supported-formats}")
    private String supportedFormats;

    private final List<WatchService> watchServices = new ArrayList<>();
    private ExecutorService executor;
    private java.util.concurrent.ScheduledExecutorService scheduledExecutor;
    private volatile boolean running = true;

    private List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void startWatching() {
        List<String> rootPaths = getRootPaths();

        for (String rootPath : rootPaths) {
            Path dir = Paths.get(rootPath);
            if (!Files.isDirectory(dir)) {
                log.warn("Media directory does not exist: {}", rootPath);
                continue;
            }

            log.info("Initial scan of media directory: {}", rootPath);
            try {
                metadataService.scanDirectory(rootPath);
            } catch (Exception e) {
                log.error("Failed to initial scan from {}", rootPath, e);
            }

            try {
                WatchService ws = FileSystems.getDefault().newWatchService();
                watchServices.add(ws);
                registerDirectory(dir, ws);
                log.info("Started watching media directory: {}", rootPath);
            } catch (IOException e) {
                log.error("Failed to start media watcher for {}", rootPath, e);
            }
        }

        if (!watchServices.isEmpty()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "media-watcher");
                t.setDaemon(true);
                return t;
            });
            executor.execute(this::watch);
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "music-periodic-scan");
                t.setDaemon(true);
                return t;
            });
            scheduledExecutor.scheduleWithFixedDelay(this::periodicScan, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Periodic scan scheduler started for music (check watcher.periodic-scan to enable)");
            log.info("Initial media scan completed, watching {} directories", watchServices.size());
        }
    }

    private void periodicScan() {
        if (!settingService.getBoolean("watcher.periodic-scan", false)) return;
        try {
            for (String rootPath : getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
        } catch (Exception e) {
            log.warn("Periodic music scan failed: {}", e.getMessage());
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

                        if (Files.isDirectory(fullPath)) {
                            try {
                                registerDirectory(fullPath, ws);
                                log.info("Registered new subdirectory: {}", fullPath);
                            } catch (IOException e) {
                                log.warn("Failed to register new subdirectory: {}", fullPath, e);
                            }
                            continue;
                        }

                        if (Files.isRegularFile(fullPath) && isSupportedFormat(fileName.toString())) {
                            log.info("Detected media file change: {}", fullPath);
                            executor.submit(() -> {
                                try {
                                    Thread.sleep(3000);
                                    if (Files.exists(fullPath) && Files.size(fullPath) > 0) {
                                        metadataService.extractAndSaveMetadata(fullPath.toString());
                                        log.info("Auto-indexed: {}", fileName);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to auto-index: {}", fileName, e);
                                }
                            });
                        }
                    }
                    key.reset();
                } catch (Exception e) {
                    log.error("Error in media watcher", e);
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
        Set<String> formats = Set.of(supportedFormats.split(","));
        return formats.contains(ext);
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
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        log.info("Stopped watching media directories");
    }
}
