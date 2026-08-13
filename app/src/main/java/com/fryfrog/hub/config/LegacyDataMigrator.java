package com.fryfrog.hub.config;

import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.FavoriteRepository;
import com.fryfrog.hub.video.repository.WatchProgressRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 一次性迁移旧的全局观看进度与收藏数据到初始管理员（admin）。
 * 多用户体系上线前，watch_progress 与 videos/video_series 上的 favorite
 * 是整个媒体库共享的，这里把它们归到新建账号体系中的 admin。
 * 幂等：每次重启只会迁移仍然缺少用户归属的记录。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyDataMigrator implements ApplicationRunner {

    private final EntityManager entityManager;
    private final FavoriteRepository favoriteRepository;
    private final WatchProgressRepository watchProgressRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long adminId = findFirstAdminId();
        if (adminId == null) {
            return;
        }
        try {
            migrateWatchProgress(adminId);
        } catch (RuntimeException e) {
            log.warn("[Migrate] watch_progress 迁移跳过: {}", e.getMessage());
        }
        try {
            migrateVideoFavorites(adminId);
        } catch (RuntimeException e) {
            log.warn("[Migrate] 视频收藏迁移跳过（可能已是新库）: {}", e.getMessage());
        }
        try {
            migrateSeriesFavorites(adminId);
        } catch (RuntimeException e) {
            log.warn("[Migrate] 系列收藏迁移跳过（可能已是新库）: {}", e.getMessage());
        }
    }

    private Long findFirstAdminId() {
        List<?> result = entityManager.createNativeQuery(
                        "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id ASC LIMIT 1")
                .getResultList();
        if (result.isEmpty()) {
            return null;
        }
        return ((Number) result.get(0)).longValue();
    }

    private void migrateWatchProgress(Long adminId) {
        transactionTemplate.executeWithoutResult(status -> {
            List<WatchProgress> legacy = watchProgressRepository.findByUserIdIsNull();
            int migrated = 0;
            for (WatchProgress wp : legacy) {
                long videoId = wp.getVideo().getId();
                if (watchProgressRepository.findByUserIdAndVideo_Id(adminId, videoId).isEmpty()) {
                    wp.setUserId(adminId);
                    watchProgressRepository.save(wp);
                    migrated++;
                } else {
                    watchProgressRepository.delete(wp);
                }
            }
            if (migrated > 0) {
                log.info("[Migrate] 已将 {} 条全局观看进度迁移到 admin", migrated);
            }
        });
    }

    private void migrateVideoFavorites(Long adminId) {
        List<?> result = entityManager.createNativeQuery("SELECT id FROM videos WHERE favorite = TRUE").getResultList();
        int migrated = 0;
        for (Object row : result) {
            long videoId = ((Number) row).longValue();
            if (favoriteRepository.findByUserIdAndContentTypeAndContentId(adminId, Favorite.TYPE_VIDEO, videoId).isEmpty()) {
                favoriteRepository.save(Favorite.builder()
                        .userId(adminId).contentType(Favorite.TYPE_VIDEO).contentId(videoId).build());
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("[Migrate] 已将 {} 条视频收藏迁移到 admin", migrated);
        }
    }

    private void migrateSeriesFavorites(Long adminId) {
        List<?> result = entityManager.createNativeQuery("SELECT id FROM video_series WHERE favorite = TRUE").getResultList();
        int migrated = 0;
        for (Object row : result) {
            long seriesId = ((Number) row).longValue();
            if (favoriteRepository.findByUserIdAndContentTypeAndContentId(adminId, Favorite.TYPE_SERIES, seriesId).isEmpty()) {
                favoriteRepository.save(Favorite.builder()
                        .userId(adminId).contentType(Favorite.TYPE_SERIES).contentId(seriesId).build());
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("[Migrate] 已将 {} 条系列收藏迁移到 admin", migrated);
        }
    }
}