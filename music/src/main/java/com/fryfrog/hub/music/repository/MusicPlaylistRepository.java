package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicPlaylist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MusicPlaylistRepository extends JpaRepository<MusicPlaylist, Long> {

    List<MusicPlaylist> findByUserIdOrderByCreatedAtAsc(Long userId);

    List<MusicPlaylist> findByIsPublicTrueOrderByCreatedAtAsc();

    Optional<MusicPlaylist> findByIdAndUserId(Long id, Long userId);
}
