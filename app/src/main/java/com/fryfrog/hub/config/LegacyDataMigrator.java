package com.fryfrog.hub.config;

import com.fryfrog.hub.video.model.WatchProgress;
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
 * 一次性迁移旧的全局观看进度数据到初始管理员（admin）。
 * 多用户体系上线前，watch_progress 是整个媒体库共享的，这里把它们归到
 * 新建账号体系中的 admin。
 * 幂等：每次重启只会迁移仍然缺少用户归属的记录。
 * （旧的 favorite 布尔列迁移已被移除：该列已随收藏表改造删除。）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyDataMigrator implements ApplicationRunner {

    private final EntityManager entityManager;
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
}