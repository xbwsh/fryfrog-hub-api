package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MusicRatingRepository extends JpaRepository<MusicRating, Long> {

    Optional<MusicRating> findByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    List<MusicRating> findByUserIdAndTargetType(Long userId, String targetType);

    void deleteByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);
}
