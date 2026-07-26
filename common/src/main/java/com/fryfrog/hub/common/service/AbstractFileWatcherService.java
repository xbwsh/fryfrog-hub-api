package com.fryfrog.hub.common.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 基于 {@link WatchService}（inotify）的抽象文件监听服务基类。
 * <p>
 * 子类只需实现：
 * <ul>
 *   <li>{@link #getRootPaths()} — 返回要监听的根目录列表</li>
 *   <li>{@link #getWatchedExtensions()} — 返回需要触发处理的后缀名集合（小写）</li>
 *   <li>{@link #processDirectory(Path)} — 新文件稳定后对目录执行的处理</li>
 * </ul>
 * <p>
 * 启动时递归注册所有根目录及其子目录，监听 {@code ENTRY_CREATE} 事件。
 * 新文件进入 debounce 队列（默认 5 秒），连续创建会重置计时。
 * 新目录被自动递归注册。
 * <p>
 * 当 {@code getRootPaths()} 返回空列表时自动跳过，不会启动监听。
 * 在不支持 {@code WatchService} 的环境（NFS、Docker Desktop）上静默降级。
 */
@Slf4j
public abstract class AbstractFileWatcherService {

    private WatchService watchService;
    private ScheduledExecutorService debounceExecutor;
    private volatile boolean running = false;
    private final Map<Path, ScheduledFuture<?>> debounceTasks = new ConcurrentHashMap<>();

    // ==================== 子类必须实现 ====================

    /** 要监听的根目录列表（返回空列表则跳过启动） */
    protected abstract List<String> getRootPaths();

    /** 关心的文件扩展名集合（小写，不含点号，如 "mp4", "mkv"） */
    protected abstract Set<String> getWatchedExtensions();

    /**
     * 目录中新文件稳定后的处理回调。
     * @param directory 新文件所在目录（已过 debounce 期，文件应已写入完毕）
     */
    protected abstract void processDirectory(Path directory);

    // ==================== 可选覆盖 ====================

    /** Debounce 稳定时间（秒），默认 5 */
    protected int getDebounceSeconds() {
        return 5;
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    public final void start() {
        List<String> rootPaths = getRootPaths();
        Set<String> extensions = getWatchedExtensions();
        if (rootPaths.isEmpty()) {
            log.info("[{}] No root paths configured, file watcher disabled", tag());
            return;
        }
        if (extensions.isEmpty()) {
            log.warn("[{}] No watched extensions configured, file watcher disabled", tag());
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("[{}] WatchService not supported on this platform: {}", tag(), e.getMessage());
            return;
        }

        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, tag() + "-debounce");
            t.setDaemon(true);
            return t;
        });

        int registered = 0;
        for (String rootPath : rootPaths) {
            Path root = Paths.get(rootPath);
            if (!Files.isDirectory(root)) {
                log.warn("[{}] Root path not a directory, skipping: {}", tag(), rootPath);
                continue;
            }
            registered += registerRecursive(root);
        }

        if (registered == 0) {
            log.warn("[{}] No directories registered for watching", tag());
            return;
        }

        running = true;
        Thread watcherThread = new Thread(this::pollLoop, tag() + "-watch");
        watcherThread.setDaemon(true);
        watcherThread.start();

        log.info("[{}] File watcher started: {} root paths, {} directories registered",
                tag(), rootPaths.size(), registered);
    }

    @PreDestroy
    public final void stop() {
        running = false;
        if (debounceExecutor != null) {
            debounceExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("[{}] Error closing WatchService: {}", tag(), e.getMessage());
            }
        }
        log.info("[{}] File watcher stopped", tag());
    }

    // ==================== 注册目录树 ====================

    private int registerRecursive(Path dir) {
        AtomicInteger count = new AtomicInteger(0);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subDir, BasicFileAttributes attrs) {
                    if (registerDir(subDir)) count.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("[{}] Skip unreadable path: {} ({})", tag(), file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[{}] Failed to walk directory tree: {} ({})", tag(), dir, e.getMessage());
        }
        return count.get();
    }

    private boolean registerDir(Path dir) {
        try {
            dir.register(watchService, ENTRY_CREATE);
            return true;
        } catch (IOException e) {
            log.debug("[{}] Cannot register directory: {} ({})", tag(), dir, e.getMessage());
            return false;
        }
    }

    // ==================== 事件轮询 ====================

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

                    if (Files.isDirectory(fullPath)) {
                        log.debug("[{}] New directory detected, registering: {}", tag(), fullPath);
                        registerRecursive(fullPath);
                        continue;
                    }

                    if (!isWatchedFile(fullPath)) continue;

                    log.debug("[{}] New file detected: {}", tag(), fullPath);
                    scheduleDebouncedScan(watchDir);
                }

                if (!key.reset()) {
                    log.warn("[{}] Watch key invalid, directory may have been deleted: {}", tag(), watchDir);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                log.error("[{}] Error in watch loop: {}", tag(), e.getMessage(), e);
            }
        }
    }

    private boolean isWatchedFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase();
        return getWatchedExtensions().contains(ext);
    }

    // ==================== Debounce ====================

    private void scheduleDebouncedScan(Path dir) {
        ScheduledFuture<?> existing = debounceTasks.remove(dir);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> task = debounceExecutor.schedule(() -> {
            debounceTasks.remove(dir);
            try {
                processDirectory(dir);
            } catch (Exception e) {
                log.warn("[{}] Failed to process directory {}: {}", tag(), dir, e.getMessage());
            }
        }, getDebounceSeconds(), TimeUnit.SECONDS);

        debounceTasks.put(dir, task);
    }

    // ==================== 辅助 ====================

    /** 用于日志标签的模块名称 */
    protected String tag() {
        return getClass().getSimpleName().replace("FileWatcherService", "");
    }
}
