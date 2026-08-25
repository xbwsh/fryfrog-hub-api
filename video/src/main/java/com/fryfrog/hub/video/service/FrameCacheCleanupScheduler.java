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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 定时清理任务：每天凌晨删除旧的截帧缓存。
 * <p>两类清理：
 * 1. 旧版命名缓存（-frame.jpg / -frame-v2.jpg 及 fanart 对应版本），保留当前 v3 缓存；
 * 2. 孤儿候选目录 .frames-{videoId}/（对应视频已不存在时残留，如删除后未触发清理）。
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

        Set<Long> liveVideoIds = new HashSet<>();
        Set<Path> videoDirs = new HashSet<>();
        Page<Video> page;
        do {
            page = repository.findAll(PageRequest.of(pageNum++, pageSize));
            for (Video video : page.getContent()) {
                liveVideoIds.add(video.getId());
                if (video.getFilePath() != null) {
                    Path videoDir = Paths.get(video.getFilePath()).getParent();
                    if (videoDir != null) videoDirs.add(videoDir);
                }
                removed += cleanupVideo(video);
            }
        } while (page.hasNext());

        removed += cleanupOrphanFrameDirs(liveVideoIds, videoDirs);

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

    /**
     * 删除孤儿截帧候选目录：在现存视频所在目录里查找 .frames-{id}/，
     * 若 id 不在现存视频集合中（视频已被删除但缓存目录残留），整体删除。
     */
    private int cleanupOrphanFrameDirs(Set<Long> liveVideoIds, Set<Path> videoDirs) {
        int removed = 0;
        for (Path videoDir : videoDirs) {
            try (Stream<Path> dirs = Files.list(videoDir)) {
                for (Path p : dirs.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith(".frames-")) continue;
                    Long cachedId = parseFrameDirId(name);
                    if (cachedId != null && !liveVideoIds.contains(cachedId)) {
                        if (deleteRecursively(p)) removed++;
                    }
                }
            } catch (Exception e) {
                log.debug("[Cleanup] Failed to scan frame dirs in {}: {}", videoDir, e.getMessage());
            }
        }
        return removed;
    }

    private Long parseFrameDirId(String dirName) {
        try {
            return Long.parseLong(dirName.substring(".frames-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
            log.info("[Cleanup] Removed orphan frame cache dir: {}", dir);
            return true;
        } catch (Exception e) {
            log.debug("[Cleanup] Failed to remove {}: {}", dir, e.getMessage());
            return false;
        }
    }
}
