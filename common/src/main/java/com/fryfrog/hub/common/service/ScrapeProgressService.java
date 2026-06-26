package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.dto.ScrapeProgress;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScrapeProgressService {

    private final Map<String, ScrapeProgress> progressMap = new ConcurrentHashMap<>();

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

    public void finish(String module) {
        ScrapeProgress progress = getProgress(module);
        progress.setRunning(false);
        progress.setUpdatedAt(LocalDateTime.now());
    }
}
