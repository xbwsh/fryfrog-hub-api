package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.FavoriteRepository;
import com.fryfrog.hub.video.repository.WatchProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;

/**
 * 视频删除时的关联数据清理：观看进度、收藏、截帧候选缓存目录。
 * 供扫描清理（VideoScanService）与文件监听（VideoFileWatcherService）复用，
 * 避免用户数据（进度/收藏）与磁盘缓存（.frames-{id}/）成为孤儿。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoCleanupService {

    private final WatchProgressRepository watchProgressRepository;
    private final FavoriteRepository favoriteRepository;

    /**
     * 删除视频关联的观看进度与收藏。
     */
    @Transactional
    public void deleteUserData(Long videoId) {
        watchProgressRepository.deleteByVideo_Id(videoId);
        favoriteRepository.deleteByContentTypeAndContentId(Favorite.TYPE_VIDEO, videoId);
    }

    /**
     * 批量删除多个视频关联的观看进度与收藏。
     */
    @Transactional
    public void deleteUserData(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return;
        watchProgressRepository.deleteByVideo_IdIn(videoIds);
        favoriteRepository.deleteByContentTypeAndContentIdIn(Favorite.TYPE_VIDEO, videoIds);
    }

    /**
     * 删除视频同目录下的截帧候选缓存目录（.frames-{videoId}/）。
     * 目录不存在时静默跳过。
     */
    public void deleteFrameCacheDir(Video video) {
        if (video == null || video.getFilePath() == null) return;
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        if (videoDir == null) return;
        Path cacheDir = videoDir.resolve(".frames-" + video.getId());
        if (!Files.exists(cacheDir)) return;
        try (var walk = Files.walk(cacheDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
            log.debug("[Cleanup] Removed frame cache dir: {}", cacheDir);
        } catch (Exception e) {
            log.debug("[Cleanup] Failed to remove frame cache dir {}: {}", cacheDir, e.getMessage());
        }
    }
}