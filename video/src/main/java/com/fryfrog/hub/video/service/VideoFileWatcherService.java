package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 基于 inotify 的视频文件监听服务。
 * <p>
 * 监听配置的媒体目录，新文件创建后自动触发扫描→刮削→整理→资产生成流水线。
 * 使用 debounce 机制避免批量操作时频繁触发。
 * <p>
 * 仅在 Linux Docker（bind mount）下正常工作，
 * NFS/Samba 或 Docker Desktop 共享卷不受支持（fallback 时静默降级）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoFileWatcherService {

    private final VideoPipelineService pipelineService;
    private final MediaLibraryService mediaLibraryService;

    @Value("${video.root-paths:}")
    private String rootPathsConfig;

    /** 监听事件后等待的稳定时间（秒），防批量复制风暴 */
    private static final int DEBOUNCE_SECONDS = 5;

    /** 支持监控的视频文件扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v"
    );

    private WatchService watchService;
    private ScheduledExecutorService debounceExecutor;
    private volatile boolean running = false;

    /** 路径 → 延迟任务，用于 debounce */
    private final Map<Path, ScheduledFuture<?>> debounceTasks = new ConcurrentHashMap<>();

    private List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private Long getLibraryId(String rootPath) {
        var library = mediaLibraryService.findByPath(rootPath);
        return library != null ? library.getId() : null;
    }

    @PostConstruct
    public void start() {
        List<String> rootPaths = getRootPaths();
        if (rootPaths.isEmpty()) {
            log.info("[FileWatcher] No video root paths configured, file watcher disabled");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("[FileWatcher] Failed to create WatchService (unsupported on this platform): {}", e.getMessage());
            return;
        }

        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "video-watch-debounce");
            t.setDaemon(true);
            return t;
        });

        // 递归注册所有根路径及其子目录
        int registered = 0;
        for (String rootPath : rootPaths) {
            Path root = Paths.get(rootPath);
            if (!Files.isDirectory(root)) {
                log.warn("[FileWatcher] Root path not a directory, skipping: {}", rootPath);
                continue;
            }
            registered += registerRecursive(root);
        }

        if (registered == 0) {
            log.warn("[FileWatcher] No directories registered for watching");
            return;
        }

        running = true;
        Thread watcherThread = new Thread(this::pollLoop, "video-watch-poll");
        watcherThread.setDaemon(true);
        watcherThread.start();

        log.info("[FileWatcher] Started: {} root paths, {} directories registered", rootPaths.size(), registered);
    }

    /**
     * 递归注册目录及其所有子目录到 WatchService。
     */
    private int registerRecursive(Path dir) {
        var count = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subDir, BasicFileAttributes attrs) {
                    if (registerDir(subDir)) count.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("[FileWatcher] Skip unreadable file: {} ({})", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[FileWatcher] Failed to walk directory tree: {} ({})", dir, e.getMessage());
        }
        return count.get();
    }

    private boolean registerDir(Path dir) {
        try {
            dir.register(watchService, ENTRY_CREATE);
            return true;
        } catch (IOException e) {
            log.debug("[FileWatcher] Cannot register directory: {} ({})", dir, e.getMessage());
            return false;
        }
    }

    /**
     * WatchService 轮询循环（daemon 线程中运行）。
     */
    private void pollLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                Path watchDir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    Path name = (Path) event.context();
                    Path fullPath = watchDir.resolve(name);

                    // 新目录 → 递归注册监听
                    if (Files.isDirectory(fullPath)) {
                        log.debug("[FileWatcher] New directory detected, registering: {}", fullPath);
                        registerRecursive(fullPath);
                        continue;
                    }

                    // 只处理视频文件
                    String ext = getExtension(fullPath);
                    if (ext == null || !VIDEO_EXTENSIONS.contains(ext)) continue;

                    log.debug("[FileWatcher] New video file detected: {}", fullPath);

                    // 对父目录做 debounce 延迟处理
                    scheduleDebouncedScan(watchDir);
                }

                if (!key.reset()) {
                    log.warn("[FileWatcher] Watch key invalid, directory may have been deleted: {}", watchDir);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[FileWatcher] Error in poll loop: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 对目录编排延迟扫描任务。若已有延迟任务则取消旧任务重新计数。
     */
    private void scheduleDebouncedScan(Path dir) {
        ScheduledFuture<?> existing = debounceTasks.get(dir);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> task = debounceExecutor.schedule(() -> {
            debounceTasks.remove(dir);
            executePipeline(dir);
        }, DEBOUNCE_SECONDS, TimeUnit.SECONDS);

        debounceTasks.put(dir, task);
    }

    /**
     * 在虚拟线程中执行单目录的完整流水线。
     */
    private void executePipeline(Path dir) {
        String dirPath = dir.toString();
        log.info("[FileWatcher] Processing directory (debounced): {}", dirPath);

        try {
            // 查找对应的 libraryId
            String rootPath = findRootPath(dirPath);
            Long libraryId = rootPath != null ? getLibraryId(rootPath) : null;

            // 只扫描当前目录（不是递归全量），新视频才会入库
            List<Path> videoFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                videoFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(f -> {
                            String e = getExtension(f);
                            return e != null && VIDEO_EXTENSIONS.contains(e);
                        })
                        .toList();
            }

            if (videoFiles.isEmpty()) {
                log.debug("[FileWatcher] No video files in directory, skipping: {}", dirPath);
                return;
            }

            pipelineService.runFullPipeline(dirPath, libraryId);
            log.info("[FileWatcher] Pipeline completed for: {}", dirPath);
        } catch (Exception e) {
            log.warn("[FileWatcher] Pipeline failed for {}: {}", dirPath, e.getMessage());
        }
    }

    /**
     * 从已注册的根路径中找到包含给定路径的根。
     */
    private String findRootPath(String path) {
        return getRootPaths().stream()
                .filter(root -> path.startsWith(root))
                .findFirst()
                .orElse(null);
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : null;
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (debounceExecutor != null) {
            debounceExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("[FileWatcher] Error closing WatchService: {}", e.getMessage());
            }
        }
        log.info("[FileWatcher] Stopped");
    }
}
