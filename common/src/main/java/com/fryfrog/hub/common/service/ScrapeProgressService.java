package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.dto.ScrapeProgress;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ScrapeProgressService {

    /** 任务结束后保留进度的时长，之后从内存移除，防止 Map 无限增长 */
    private static final long CLEANUP_DELAY_MINUTES = 5;

    private final Map<String, ScrapeProgress> progressMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "progress-cleanup");
                t.setDaemon(true);
                return t;
            });

    public ScrapeProgress getProgress(String module) {
        return progressMap.computeIfAbsent(module, k -> {
            ScrapeProgress p = new ScrapeProgress();
            p.setModule(k);
            p.setRunning(false);
            return p;
        });
    }

    public void start(String module, int total) {
        ScrapeProgress progress = new ScrapeProgress();
        progress.setModule(module);
        progress.setRunning(true);
        progress.setTotal(total);
        progress.setCompleted(0);
        progress.setFailed(0);
        progress.setSkipped(0);
        progress.setStartedAt(LocalDateTime.now());
        progress.setUpdatedAt(LocalDateTime.now());
        progress.getItems().clear();
        progressMap.put(module, progress);
    }

    public void updateItem(String module, String name, String status, String error) {
        ScrapeProgress progress = getProgress(module);
        ScrapeProgress.ScrapeItemStatus item = new ScrapeProgress.ScrapeItemStatus();
        item.setName(name);
        item.setStatus(status);
        item.setError(error);
        item.setProcessedAt(LocalDateTime.now());
        progress.getItems().add(item);

        switch (status) {
            case "completed" -> progress.setCompleted(progress.getCompleted() + 1);
            case "failed" -> progress.setFailed(progress.getFailed() + 1);
            case "skipped" -> progress.setSkipped(progress.getSkipped() + 1);
        }
        progress.setCurrentItem(name);
        progress.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 轻量进度更新：只更新计数和当前项，不记录明细（用于扫描等大批量场景，避免内存膨胀）
     */
    public void advance(String module, String currentItem, boolean success) {
        ScrapeProgress progress = getProgress(module);
        if (success) {
            progress.setCompleted(progress.getCompleted() + 1);
        } else {
            progress.setFailed(progress.getFailed() + 1);
        }
        progress.setCurrentItem(currentItem);
        progress.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 设置当前阶段（如 scan/scrape/assets），不改变计数
     */
    public void stage(String module, String stage) {
        ScrapeProgress progress = getProgress(module);
        progress.setStage(stage);
        progress.setUpdatedAt(LocalDateTime.now());
    }

    public void finish(String module) {
        ScrapeProgress progress = getProgress(module);
        progress.setRunning(false);
        progress.setUpdatedAt(LocalDateTime.now());
        scheduleCleanup(module);
    }

    /**
     * 任务结束后延迟移除进度条目；若期间同一 module 重新开始，则跳过清理。
     */
    private void scheduleCleanup(String module) {
        cleanupScheduler.schedule(() -> {
            ScrapeProgress progress = progressMap.get(module);
            if (progress != null && !progress.isRunning()) {
                progressMap.remove(module);
                log.debug("Removed stale progress entry: {}", module);
            }
        }, CLEANUP_DELAY_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdownNow();
    }
}
