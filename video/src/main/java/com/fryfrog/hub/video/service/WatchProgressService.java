package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.WatchProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchProgressService {

    private final WatchProgressRepository repository;
    private final VideoService videoService;
    private final TransactionTemplate transactionTemplate;

    private static final double COMPLETED_THRESHOLD = 0.95;
    private static final int MAX_RETRIES = 3;

    public WatchProgress getProgress(Long videoId) {
        return repository.findByVideo_Id(videoId).orElse(null);
    }

    public Map<Long, WatchProgress> getProgressByVideoIds(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByVideo_IdIn(videoIds).stream()
                .collect(Collectors.toMap(wp -> wp.getVideo().getId(), wp -> wp));
    }

    public WatchProgress updatePosition(Long videoId, Double positionSeconds, Double durationSeconds) {
        return retryOnLock(() -> transactionTemplate.execute(status -> {
            Video video = videoService.getVideoById(videoId);

            WatchProgress progress = repository.findByVideo_Id(videoId).orElse(new WatchProgress());
            progress.setVideo(video);
            progress.setPositionSeconds(positionSeconds);
            if (durationSeconds != null) {
                progress.setDurationSeconds(durationSeconds);
            }

            Double dur = progress.getDurationSeconds();
            if (dur != null && dur > 0) {
                progress.setCompleted(positionSeconds / dur >= COMPLETED_THRESHOLD);
            }

            WatchProgress saved = repository.save(progress);
            log.debug("Updated position for video {}: {}s", videoId, positionSeconds);
            return saved;
        }));
    }

    public WatchProgress updateWatched(Long videoId, boolean completed) {
        return retryOnLock(() -> transactionTemplate.execute(status -> {
            Video video = videoService.getVideoById(videoId);

            WatchProgress progress = repository.findByVideo_Id(videoId).orElse(new WatchProgress());
            progress.setVideo(video);
            progress.setCompleted(completed);

            if (completed && progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
                progress.setPositionSeconds(progress.getDurationSeconds());
            }

            WatchProgress saved = repository.save(progress);
            log.debug("Set video {} watched={}", videoId, completed);
            return saved;
        }));
    }

    @Transactional
    public void deleteProgress(Long videoId) {
        repository.findByVideo_Id(videoId).ifPresent(repository::delete);
    }

    private <T> T retryOnLock(Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (CannotAcquireLockException e) {
                if (attempt == MAX_RETRIES) {
                    log.warn("SQLite lock conflict after {} retries, giving up", MAX_RETRIES);
                    throw e;
                }
                log.debug("SQLite lock conflict, retrying ({}/{})", attempt, MAX_RETRIES);
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
