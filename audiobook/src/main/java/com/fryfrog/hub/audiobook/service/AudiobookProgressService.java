package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookProgressRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudiobookProgressService {

    /** 最后一轨播放超过 95% 视为听完 */
    private static final double COMPLETED_THRESHOLD = 0.95;

    private final AudiobookProgressRepository progressRepository;
    private final AudiobookRepository bookRepository;
    private final AudiobookTrackRepository trackRepository;

    public AudiobookProgress getProgress(Long userId, Long bookId) {
        return progressRepository.findByUserIdAndAudiobook_Id(userId, bookId).orElse(null);
    }

    /** bookId → 进度（列表页批量取用） */
    public Map<Long, AudiobookProgress> getProgressByBookIds(Long userId, Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();
        return progressRepository.findByUserIdAndAudiobook_IdIn(userId, bookIds).stream()
                .collect(Collectors.toMap(p -> p.getAudiobook().getId(), p -> p, (a, b) -> a));
    }

    @Transactional
    public AudiobookProgress updatePosition(Long userId, Long bookId, Integer trackIndex, Double positionSeconds) {
        Audiobook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", bookId));

        AudiobookProgress progress = progressRepository.findByUserIdAndAudiobook_Id(userId, bookId)
                .orElseGet(AudiobookProgress::new);
        progress.setUserId(userId);
        progress.setAudiobook(book);
        if (trackIndex != null) progress.setTrackIndex(trackIndex);
        if (positionSeconds != null) progress.setPositionSeconds(positionSeconds);

        autoDetectCompleted(progress, book);
        AudiobookProgress saved = progressRepository.save(progress);
        log.debug("[AudiobookProgress] user={} book={} track={} pos={}s", userId, bookId,
                progress.getTrackIndex(), progress.getPositionSeconds());
        return saved;
    }

    @Transactional
    public AudiobookProgress setCompleted(Long userId, Long bookId, boolean completed) {
        Audiobook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Audiobook", "id", bookId));

        AudiobookProgress progress = progressRepository.findByUserIdAndAudiobook_Id(userId, bookId)
                .orElseGet(() -> AudiobookProgress.builder()
                        .userId(userId).audiobook(book).trackIndex(0).positionSeconds(0d).build());

        progress.setCompleted(completed);
        if (completed && book.getPlayType().equals(Audiobook.TYPE_MULTI)) {
            // 标记听完时把位置推到最后一轨末尾
            List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(bookId);
            if (!tracks.isEmpty()) {
                AudiobookTrack last = tracks.get(tracks.size() - 1);
                progress.setTrackIndex(last.getTrackIndex());
                progress.setPositionSeconds(last.getDurationSeconds());
            }
        }
        return progressRepository.save(progress);
    }

    @Transactional
    public void deleteProgress(Long userId, Long bookId) {
        progressRepository.findByUserIdAndAudiobook_Id(userId, bookId)
                .ifPresent(progressRepository::delete);
    }

    private void autoDetectCompleted(AudiobookProgress progress, Audiobook book) {
        if (progress.getTrackIndex() == null || progress.getPositionSeconds() == null) return;
        List<AudiobookTrack> tracks = trackRepository.findByAudiobook_IdOrderByTrackIndexAsc(book.getId());
        if (tracks.isEmpty()) return;

        AudiobookTrack last = tracks.get(tracks.size() - 1);
        boolean onLastTrack = progress.getTrackIndex() >= last.getTrackIndex();
        Double lastDuration = last.getDurationSeconds();
        if (onLastTrack && lastDuration != null && lastDuration > 0
                && progress.getPositionSeconds() / lastDuration >= COMPLETED_THRESHOLD) {
            progress.setCompleted(true);
        }
    }
}
