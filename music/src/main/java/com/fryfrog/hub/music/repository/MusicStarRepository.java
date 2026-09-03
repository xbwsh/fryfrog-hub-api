package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicStar;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MusicStarRepository extends JpaRepository<MusicStar, Long> {

    Optional<MusicStar> findByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    List<MusicStar> findByUserIdAndTargetType(Long userId, String targetType);

    Page<MusicStar> findByUserIdAndTargetType(Long userId, String targetType, Pageable pageable);

    List<MusicStar> findByUserId(Long userId);

    boolean existsByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    void deleteByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);
}
