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
        return repository.findByVideo_Id(videoId).orElse(null);
    }

    public Map<Long, WatchProgress> getProgressByVideoIds(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByVideo_IdIn(videoIds).stream()
                .collect(Collectors.toMap(wp -> wp.getVideo().getId(), wp -> wp));
    }

    @Transactional
    public WatchProgress saveProgress(Long videoId, Double positionSeconds, Double durationSeconds) {
        Video video = videoService.getVideoById(videoId);

        // 用 ffprobe 的精确时长覆盖前端传来的可能不准确的值
        Double realDuration = video.getDurationSeconds();
        if (realDuration != null && realDuration > 0
                && (durationSeconds == null || durationSeconds <= 0 || durationSeconds < realDuration * 0.5)) {
            durationSeconds = realDuration;
        }

        WatchProgress progress = repository.findByVideo_Id(videoId).orElse(new WatchProgress());
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
        repository.findByVideo_Id(videoId).ifPresent(repository::delete);
    }

    @Transactional
    public WatchProgress markAsWatched(Long videoId) {
        Video video = videoService.getVideoById(videoId);

        WatchProgress progress = repository.findByVideo_Id(videoId).orElse(new WatchProgress());
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
        return setWatched(videoId, false);
    }

    @Transactional
    public WatchProgress setWatched(Long videoId, boolean completed) {
        Video video = videoService.getVideoById(videoId);

        WatchProgress progress = repository.findByVideo_Id(videoId).orElse(new WatchProgress());
        progress.setVideo(video);
        progress.setCompleted(completed);

        if (completed && progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
            progress.setPositionSeconds(progress.getDurationSeconds());
        }

        WatchProgress saved = repository.save(progress);
        log.info("Set video {} watched={}", videoId, completed);
        return saved;
    }
}
