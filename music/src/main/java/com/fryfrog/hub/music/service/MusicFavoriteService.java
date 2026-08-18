package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicRating;
import com.fryfrog.hub.music.model.MusicStar;
import com.fryfrog.hub.music.repository.MusicRatingRepository;
import com.fryfrog.hub.music.repository.MusicStarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 音乐收藏（星标）与评分，按用户隔离。
 */
@Service
@RequiredArgsConstructor
public class MusicFavoriteService {

    public static final String TYPE_SONG = "SONG";
    public static final String TYPE_ALBUM = "ALBUM";
    public static final String TYPE_ARTIST = "ARTIST";

    private final MusicStarRepository starRepository;
    private final MusicRatingRepository ratingRepository;

    @Transactional
    public void setStar(long userId, String type, long targetId, boolean starred) {
        if (starred) {
            starRepository.findByUserIdAndTargetTypeAndTargetId(userId, type, targetId)
                    .orElseGet(() -> starRepository.save(MusicStar.builder()
                            .userId(userId).targetType(type).targetId(targetId).build()));
        } else {
            starRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
        }
    }

    public Set<Long> starredIds(long userId, String type, List<Long> ids) {
        List<MusicStar> stars = starRepository.findByUserIdAndTargetType(userId, type);
        return stars.stream()
                .filter(s -> ids.contains(s.getTargetId()))
                .map(MusicStar::getTargetId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 该类型下用户所有收藏的目标 ID（用于 getStarred2 等全量场景）。 */
    public Set<Long> starredIdsOfType(long userId, String type) {
        return starRepository.findByUserIdAndTargetType(userId, type).stream()
                .map(MusicStar::getTargetId)
                .collect(java.util.stream.Collectors.toSet());
    }

    public boolean isStarred(long userId, String type, long targetId) {
        return starRepository.existsByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
    }

    @Transactional
    public void setRating(long userId, String type, long targetId, int rating) {
        if (rating <= 0) {
            ratingRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, type, targetId);
            return;
        }
        int clamped = Math.min(5, Math.max(1, rating));
        MusicRating existing = ratingRepository.findByUserIdAndTargetTypeAndTargetId(userId, type, targetId)
                .orElseGet(() -> {
                    MusicRating r = new MusicRating();
                    r.setUserId(userId);
                    r.setTargetType(type);
                    r.setTargetId(targetId);
                    return r;
                });
        existing.setRating(clamped);
        ratingRepository.save(existing);
    }

    public Integer getRating(long userId, String type, long targetId) {
        return ratingRepository.findByUserIdAndTargetTypeAndTargetId(userId, type, targetId)
                .map(MusicRating::getRating)
                .orElse(null);
    }

    public List<MusicRating> getRatings(long userId, String type) {
        return ratingRepository.findByUserIdAndTargetType(userId, type);
    }

    public Map<Long, Integer> ratingMap(long userId, String type, List<Long> ids) {
        Map<Long, Integer> map = new java.util.LinkedHashMap<>();
        ratingRepository.findByUserIdAndTargetType(userId, type).stream()
                .filter(r -> ids.contains(r.getTargetId()))
                .forEach(r -> map.put(r.getTargetId(), r.getRating()));
        return map;
    }
}
