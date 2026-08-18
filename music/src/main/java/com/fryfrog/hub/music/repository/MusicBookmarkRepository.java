package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicBookmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MusicBookmarkRepository extends JpaRepository<MusicBookmark, Long> {

    List<MusicBookmark> findByUserIdOrderByCreatedAtMillisAsc(Long userId);

    Optional<MusicBookmark> findByUserIdAndSong_Id(Long userId, Long songId);

    void deleteByUserIdAndSong_Id(Long userId, Long songId);

    void deleteBySong_Id(Long songId);
}
