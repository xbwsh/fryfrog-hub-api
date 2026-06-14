package com.fryfrog.hub.video.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoWatcherService {

    private final VideoService metadataService;

    @Value("${hub.video.root-path}")
    private String rootPath;

    @Value("${hub.video.supported-formats}")
    private String supportedFormats;

    private WatchService watchService;
    private ExecutorService executor;
    private volatile boolean running = true;

    private final Set<String> scrapingPaths = ConcurrentHashMap.newKeySet();

    public void addScrapingPath(String path) {
        scrapingPaths.add(path);
    }

    public void removeScrapingPath(String path) {
        scrapingPaths.remove(path);
    }

    @PostConstruct
    public void startWatching() {
        Path dir = Paths.get(rootPath);
        if (!Files.isDirectory(dir)) {
            log.warn("Video directory does not exist: {}", rootPath);
            return;
        }

        log.info("Initial scan of video directory: {}", rootPath);
        try {
            metadataService.scanDirectory(rootPath);
            log.info("Initial video scan completed");
        } catch (Exception e) {
            log.error("Failed to initial scan videos", e);
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "video-watcher");
                t.setDaemon(true);
                return t;
            });

            registerDirectory(dir);
            executor.execute(this::watch);
            log.info("Started watching video directory: {}", rootPath);
        } catch (IOException e) {
            log.error("Failed to start video watcher", e);
        }
    }

    private void registerDirectory(Path dir) throws IOException {
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(subDir -> {
                try {
                    registerDirectory(subDir);
                } catch (IOException e) {
                    log.warn("Failed to register subdirectory: {}", subDir, e);
                }
            });
        }
    }

    private void watch() {
        while (running) {
            try {
                WatchKey key = watchService.take();
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

                    if (Files.isRegularFile(fullPath) && isSupportedFormat(fileName.toString())) {
                        log.info("Detected video file change: {}", fullPath);
                        try {
                            metadataService.extractAndSaveMetadata(fullPath.toString());
                            log.info("Auto-indexed video: {}", fileName);
                        } catch (Exception e) {
                            log.warn("Failed to auto-index video: {}", fileName, e);
                        }
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in video watcher", e);
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
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Failed to close watch service", e);
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
        log.info("Stopped watching video directory");
    }
}
