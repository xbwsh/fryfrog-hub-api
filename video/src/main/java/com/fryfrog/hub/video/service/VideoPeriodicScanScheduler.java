package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.PeriodicScanScheduler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 视频库定期扫描：除发现新文件外，也会清理文件系统中已经不存在的数据库记录。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoPeriodicScanScheduler {

    private final PeriodicScanScheduler scheduler;
    private final MediaLibraryService mediaLibraryService;
    private final VideoScanService scanService;

    @PostConstruct
    public void register() {
        scheduler.registerTask(() -> mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .forEach(this::scanLibrary));
    }

    private void scanLibrary(MediaLibrary library) {
        try {
            scanService.scanAndSave(library.getPath(), library.getId());
        } catch (Exception e) {
            log.warn("[VideoScan] Periodic scan failed '{}': {}", library.getName(), e.getMessage());
        }
    }
}
