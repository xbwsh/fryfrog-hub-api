package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.WatchProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WatchProgressRepository extends JpaRepository<WatchProgress, Long> {

    Optional<WatchProgress> findByUserIdAndVideo_Id(Long userId, Long videoId);

    List<WatchProgress> findByUserIdAndVideo_IdIn(Long userId, Collection<Long> videoIds);

    void deleteByVideo_Id(Long videoId);

    void deleteByVideo_IdIn(Collection<Long> videoIds);

    List<WatchProgress> findByUserIdIsNull();
}