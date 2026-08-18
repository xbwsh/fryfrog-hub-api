package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicBookmark;
import com.fryfrog.hub.music.model.MusicPlayQueue;
import com.fryfrog.hub.music.repository.MusicBookmarkRepository;
import com.fryfrog.hub.music.repository.MusicPlayQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 播放队列与书签，按用户隔离。
 */
@Service
@RequiredArgsConstructor
public class MusicPlayStateService {

    private final MusicPlayQueueRepository queueRepository;
    private final MusicBookmarkRepository bookmarkRepository;
    private final com.fryfrog.hub.music.repository.MusicSongRepository songRepository;

    @Transactional
    public MusicPlayQueue savePlayQueue(long userId, List<Long> songIds, Long currentSongId, Double positionSeconds, long changedAtMillis) {
        MusicPlayQueue queue = queueRepository.findByUserId(userId).orElseGet(() -> {
            MusicPlayQueue q = new MusicPlayQueue();
            q.setUserId(userId);
            return q;
        });
        queue.setEntryIds(songIds == null ? "" : String.join(",", songIds.stream().map(String::valueOf).toList()));
        queue.setCurrentSongId(currentSongId);
        queue.setPositionSeconds(positionSeconds);
        queue.setChangedAtMillis(changedAtMillis);
        return queueRepository.save(queue);
    }

    public MusicPlayQueue getPlayQueue(long userId) {
        return queueRepository.findByUserId(userId).orElse(null);
    }

    @Transactional
    public void deletePlayQueue(long userId) {
        queueRepository.deleteByUserId(userId);
    }

    public static List<Long> parseEntryIds(String entryIds) {
        if (entryIds == null || entryIds.isBlank()) return List.of();
        return Arrays.stream(entryIds.split(","))
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .toList();
    }

    @Transactional
    public MusicBookmark createBookmark(long userId, Long songId, Double positionSeconds, String comment) {
        MusicBookmark bookmark = bookmarkRepository.findByUserIdAndSong_Id(userId, songId)
                .orElseGet(() -> {
                    MusicBookmark b = new MusicBookmark();
                    b.setUserId(userId);
                    return b;
                });
        bookmark.setSong(songRepository.getReferenceById(songId));
        bookmark.setPositionSeconds(positionSeconds);
        bookmark.setComment(comment);
        bookmark.setCreatedAtMillis(System.currentTimeMillis());
        return bookmarkRepository.save(bookmark);
    }

    public List<MusicBookmark> getBookmarks(long userId) {
        return bookmarkRepository.findByUserIdOrderByCreatedAtMillisAsc(userId);
    }

    @Transactional
    public void deleteBookmark(long userId, Long songId) {
        bookmarkRepository.deleteByUserIdAndSong_Id(userId, songId);
    }
}
