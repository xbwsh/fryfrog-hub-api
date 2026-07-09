package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.ebook.model.Ebook;
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
public class EbookWatcherService {

    private final EbookService metadataService;
    private final EbookMetadataScrapeService scrapeService;
    private final SystemSettingService settingService;
    private final PeriodicScanScheduler scanScheduler;

    @Value("${hub.ebook.root-paths:./media-library/ebook}")
    private String rootPathsConfig;

    @Value("${hub.ebook.supported-formats}")
    private String supportedFormats;

    private final List<WatchService> watchServices = new ArrayList<>();
    private ExecutorService executor;
    private volatile boolean running = true;
    private final java.util.concurrent.locks.ReentrantLock scrapeLock = new java.util.concurrent.locks.ReentrantLock();

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
                log.warn("Ebook directory does not exist: {}", rootPath);
                continue;
            }

            try {
                WatchService ws = FileSystems.getDefault().newWatchService();
                watchServices.add(ws);
                registerDirectory(dir, ws);
                log.info("Started watching ebook directory: {}", rootPath);
            } catch (IOException e) {
                log.error("Failed to start ebook watcher for {}", rootPath, e);
            }
        }

        if (!watchServices.isEmpty()) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.execute(this::watch);
            scanScheduler.registerTask(this::periodicScan);
            log.info("Ebook watcher registered, watching {} directories", watchServices.size());
        }
    }

    private void periodicScan() {
        try {
            for (String rootPath : getRootPaths()) {
                metadataService.scanDirectory(rootPath);
            }
            metadataService.organizeAll();
            scrapeService.autoScrapeAll();
        } catch (Exception e) {
            log.warn("Periodic ebook scan failed: {}", e.getMessage());
        }
    }

    private void registerDirectory(Path dir, WatchService ws) throws IOException {
        dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

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

                        // 文件删除事件
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            log.info("Detected file deleted: {} (format={})", fullPath, fileName);
                            if (isSupportedFormat(fileName.toString())) {
                                executor.submit(() -> {
                                    try {
                                        Thread.sleep(5000);
                                        log.info("Running cleanup after file deletion: {}", fullPath);
                                        scrapeLock.lock();
                                        try {
                                            int removed = metadataService.cleanupInvalidRecords();
                                            log.info("Cleanup completed: removed {} invalid records", removed);
                                        } finally {
                                            scrapeLock.unlock();
                                        }
                                    } catch (Exception e) {
                                        log.warn("Failed to cleanup after file deletion: {}", e.getMessage());
                                    }
                                });
                            } else {
                                log.info("Skipped cleanup for unsupported format: {}", fileName);
                            }
                            continue;
                        }

                        // 新建/修改事件
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
                            log.debug("Detected ebook file change: {}", fullPath);
                            executor.submit(() -> {
                                try {
                                    Thread.sleep(3000);
                                    if (Files.exists(fullPath) && Files.size(fullPath) > 0) {
                                        Ebook ebook = metadataService.extractAndSaveMetadata(fullPath.toString());
                                        log.debug("Auto-indexed ebook: {}", fileName);
                                        if (ebook != null && ebook.getBangumiId() == null && ebook.getOpenLibraryId() == null) {
                                            scrapeLock.lock();
                                            try {
                                                scrapeService.autoScrapeEbook(ebook);
                                                log.debug("Auto-scraped ebook: {}", fileName);
                                            } catch (Exception e) {
                                                log.warn("Failed to auto-scrape ebook: {}", fileName, e);
                                            } finally {
                                                scrapeLock.unlock();
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to auto-index ebook: {}", fileName, e);
                                }
                            });
                        }
                    }
                    key.reset();
                } catch (Exception e) {
                    log.error("Error in ebook watcher", e);
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
        log.info("Stopped watching ebook directories");
    }
}
