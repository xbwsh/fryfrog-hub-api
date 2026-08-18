package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicPlayQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MusicPlayQueueRepository extends JpaRepository<MusicPlayQueue, Long> {

    Optional<MusicPlayQueue> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
