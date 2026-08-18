package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 播放统计与「正在播放」状态。与 Navidrome 一致：stream 不记播放，
 * 只有 scrobble(submission=true) 才累加播放次数。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MusicPlayStatService {

    public record NowPlaying(String username, String clientName, MusicSong song, long startedAtMillis) {}

    private final ConcurrentMap<Long, NowPlaying> nowPlaying = new ConcurrentHashMap<>();

    private final com.fryfrog.hub.music.repository.MusicPlayStatRepository playStatRepository;
    private final MusicSongRepository songRepository;
    private final UserService userService;

    /** 注册正在播放（getNowPlaying 展示）。 */
    public void registerNowPlaying(long userId, String username, String clientName, long songId, long startedAtMillis) {
        MusicSong song = songRepository.findById(songId).orElse(null);
        if (song == null) return;
        nowPlaying.put(userId, new NowPlaying(username, clientName, song, startedAtMillis));
    }

    /** 清空正在播放（玩家停止时）。 */
    public void clearNowPlaying(long userId) {
        nowPlaying.remove(userId);
    }

    public List<NowPlaying> getNowPlaying() {
        return List.copyOf(nowPlaying.values());
    }

    /** scrobble 提交：累加播放次数并记录最近播放时间。 */
    @Transactional
    public void scrobble(long userId, long songId, Long timestampMillis, boolean submission) {
        if (!submission) {
            registerNowPlaying(userId, usernameOf(userId), "subsonic", songId,
                    timestampMillis != null ? timestampMillis : System.currentTimeMillis());
            return;
        }
        MusicSong song = songRepository.findById(songId).orElse(null);
        if (song == null) return;
        var stat = playStatRepository.findBySong_IdAndUserId(songId, userId)
                .orElseGet(() -> {
                    var s = new com.fryfrog.hub.music.model.MusicPlayStat();
                    s.setSong(song);
                    s.setUserId(userId);
                    s.setPlayCount(0);
                    return s;
                });
        stat.setPlayCount(stat.getPlayCount() + 1);
        stat.setLastPlayedAt(LocalDateTime.now());
        playStatRepository.save(stat);
        log.debug("[MusicScrobble] user={} song={} count={}", userId, songId, stat.getPlayCount());
    }

    private String usernameOf(long userId) {
        if (userId == com.fryfrog.hub.common.security.UserContext.ANONYMOUS_ID) return "anonymous";
        try {
            User user = userService.getUser(userId);
            return user.getUsername();
        } catch (Exception e) {
            return String.valueOf(userId);
        }
    }

    public Integer getPlayCount(long userId, long songId) {
        return playStatRepository.findBySong_IdAndUserId(songId, userId)
                .map(com.fryfrog.hub.music.model.MusicPlayStat::getPlayCount)
                .orElse(0);
    }

    public Map<Long, Integer> playCountMap(long userId, List<Long> songIds) {
        Map<Long, Integer> map = new java.util.LinkedHashMap<>();
        playStatRepository.findBySong_IdIn(songIds).stream()
                .filter(s -> s.getUserId() == null || s.getUserId() == userId)
                .forEach(s -> map.merge(s.getSong().getId(), s.getPlayCount(), Integer::max));
        return map;
    }
}
