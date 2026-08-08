package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 定时清理任务：每天凌晨删除旧的截帧缓存版本。
 * 清理 -frame.jpg / -frame-v2.jpg（及 fanart 对应版本），保留当前 v3 缓存。
 * v3 生成后旧版本不再被读取，删除可释放磁盘空间。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FrameCacheCleanupScheduler {

    private final VideoRepository repository;
    private final NfoService nfoService;

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldFrameCaches() {
        log.info("[Cleanup] Starting old frame cache cleanup (daily 02:00)");
        int removed = 0;
        int pageNum = 0;
        final int pageSize = 100;

        Page<Video> page;
        do {
            page = repository.findAll(PageRequest.of(pageNum++, pageSize));
            for (Video video : page.getContent()) {
                removed += cleanupVideo(video);
            }
        } while (page.hasNext());

        log.info("[Cleanup] Old frame cache cleanup complete: {} files removed", removed);
    }

    private int cleanupVideo(Video video) {
        if (video.getFilePath() == null) return 0;
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        if (videoDir == null) return 0;

        String baseName = nfoService.getBaseName(video.getFileName());
        // 旧版本缓存（v1 pad 版、v2 crop 版），v3 为当前版本不清理
        Path[] oldCaches = {
                videoDir.resolve(baseName + "-frame.jpg"),
                videoDir.resolve(baseName + "-frame-v2.jpg"),
                videoDir.resolve(baseName + "-fanart-frame.jpg"),
                videoDir.resolve(baseName + "-fanart-frame-v2.jpg")
        };

        int removed = 0;
        for (Path cache : oldCaches) {
            try {
                if (Files.deleteIfExists(cache)) {
                    log.debug("[Cleanup] Removed old frame cache: {}", cache);
                    removed++;
                }
            } catch (Exception e) {
                log.debug("[Cleanup] Failed to remove {}: {}", cache, e.getMessage());
            }
        }
        return removed;
    }
}
