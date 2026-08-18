package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 音乐库定期扫描：注册到共享 {@link PeriodicScanScheduler}，
 * 定时增量刷新所有启用中的 MUSIC 资源库。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MusicPeriodicScanScheduler {

    private final PeriodicScanScheduler scheduler;
    private final MediaLibraryService mediaLibraryService;
    private final MusicScanService scanService;

    @PostConstruct
    public void register() {
        scheduler.registerTask(() -> {
            List<MediaLibrary> musicLibraries = mediaLibraryService.getEnabledLibraries().stream()
                    .filter(MediaLibrary::isMusicType)
                    .toList();
            for (MediaLibrary lib : musicLibraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[MusicScan] Periodic scan failed '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
    }
}