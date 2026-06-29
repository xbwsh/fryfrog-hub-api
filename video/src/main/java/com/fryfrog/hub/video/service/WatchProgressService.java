package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.repository.WatchProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchProgressService {

    private final WatchProgressRepository repository;
    private final VideoService videoService;

    private static final double COMPLETED_THRESHOLD = 0.95;

    public WatchProgress getProgress(Long videoId) {
        return repository.findByVideoId(videoId).orElse(null);
    }

    public Map<Long, WatchProgress> getProgressByVideoIds(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByVideoIdIn(videoIds).stream()
                .collect(Collectors.toMap(wp -> wp.getVideo().getId(), wp -> wp));
    }

    @Transactional
    public WatchProgress saveProgress(Long videoId, Double positionSeconds, Double durationSeconds) {
        Video video = videoService.getVideoById(videoId);

        WatchProgress progress = repository.findByVideoId(videoId).orElse(new WatchProgress());
        progress.setVideo(video);
        progress.setPositionSeconds(positionSeconds);
        progress.setDurationSeconds(durationSeconds);

        if (durationSeconds != null && durationSeconds > 0) {
            progress.setCompleted(positionSeconds / durationSeconds >= COMPLETED_THRESHOLD);
        }

        WatchProgress saved = repository.save(progress);
        log.debug("Saved progress for video {}: {}s / {}s", videoId, positionSeconds, durationSeconds);
        return saved;
    }

    @Transactional
    public void deleteProgress(Long videoId) {
        repository.findByVideoId(videoId).ifPresent(repository::delete);
    }

    @Transactional
    public WatchProgress markAsWatched(Long videoId) {
        Video video = videoService.getVideoById(videoId);

        WatchProgress progress = repository.findByVideoId(videoId).orElse(new WatchProgress());
        progress.setVideo(video);
        progress.setCompleted(true);

        if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
            progress.setPositionSeconds(progress.getDurationSeconds());
        }

        WatchProgress saved = repository.save(progress);
        log.info("Marked video {} as watched", videoId);
        return saved;
    }

    @Transactional
    public WatchProgress markAsUnwatched(Long videoId) {
        WatchProgress progress = repository.findByVideoId(videoId).orElse(null);
        if (progress != null) {
            progress.setCompleted(false);
            repository.save(progress);
            log.info("Marked video {} as unwatched", videoId);
        }
        return progress;
    }
}
