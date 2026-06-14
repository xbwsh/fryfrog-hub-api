package com.fryfrog.hub.comic.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicWatcherService {

    private final ComicMetadataService metadataService;

    @Value("${hub.comic.root-path}")
    private String rootPath;

    @Value("${hub.comic.supported-formats}")
    private String supportedFormats;

    private WatchService watchService;
    private ExecutorService executor;
    private volatile boolean running = true;

    @PostConstruct
    public void startWatching() {
        Path dir = Paths.get(rootPath);
        if (!Files.isDirectory(dir)) {
            log.warn("Comic directory does not exist: {}", rootPath);
            return;
        }

        log.info("Initial scan of comic directory: {}", rootPath);
        try {
            metadataService.scanDirectory(rootPath);
            log.info("Initial comic scan completed");
        } catch (Exception e) {
            log.error("Failed to initial scan comics", e);
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "comic-watcher");
                t.setDaemon(true);
                return t;
            });

            registerDirectory(dir);
            executor.execute(this::watch);
            log.info("Started watching comic directory: {}", rootPath);
        } catch (IOException e) {
            log.error("Failed to start comic watcher", e);
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

                    if (Files.isRegularFile(fullPath) && isSupportedFormat(fileName.toString())) {
                        log.info("Detected comic file change: {}", fullPath);
                        try {
                            metadataService.extractAndSaveMetadata(fullPath.toString());
                            log.info("Auto-indexed comic: {}", fileName);
                        } catch (Exception e) {
                            log.warn("Failed to auto-index comic: {}", fileName, e);
                        }
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in comic watcher", e);
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
        log.info("Stopped watching comic directory");
    }
}