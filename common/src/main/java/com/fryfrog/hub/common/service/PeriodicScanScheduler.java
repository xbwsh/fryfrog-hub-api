package com.fryfrog.hub.common.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class PeriodicScanScheduler implements ApplicationListener<ContextRefreshedEvent> {

    private static final String INTERVAL_KEY = "hub.watcher.periodic-scan-interval";
    private static final String SWITCH_KEY = "hub.watcher.periodic-scan";

    @Value("${watcher.periodic-scan-interval:30}")
    private int configInterval = 30;

    private final SystemSettingService settingService;
    private ScheduledExecutorService scheduler;
    private final List<Runnable> scanTasks = new ArrayList<>();

    public PeriodicScanScheduler(SystemSettingService settingService) {
        this.settingService = settingService;
    }

    public void registerTask(Runnable task) {
        scanTasks.add(task);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (scanTasks.isEmpty()) {
            log.info("No periodic scan tasks registered, scheduler not started");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-scan");
            t.setDaemon(true);
            return t;
        });
        int interval = settingService.getInteger(INTERVAL_KEY, configInterval);
        scheduler.scheduleWithFixedDelay(this::runAll, 0, interval, TimeUnit.SECONDS);
        log.info("Shared periodic scan scheduler started: {} tasks, interval {}s", scanTasks.size(), interval);
    }

    public void updateInterval(int seconds) {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-scan");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runAll, seconds, seconds, TimeUnit.SECONDS);
        log.info("Periodic scan interval updated to {}s", seconds);
    }

    private void runAll() {
        if (scanTasks.isEmpty()) return;
        if (!settingService.getBoolean(SWITCH_KEY, true)) return;
        com.fryfrog.hub.common.util.DatabaseWriteLock.runInWriteLock(() -> {
            for (Runnable task : scanTasks) {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("Periodic scan task failed: {}", e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
