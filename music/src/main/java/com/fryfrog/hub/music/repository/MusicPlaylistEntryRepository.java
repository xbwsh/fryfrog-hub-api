package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicPlaylistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MusicPlaylistEntryRepository extends JpaRepository<MusicPlaylistEntry, Long> {

    List<MusicPlaylistEntry> findByPlaylist_IdOrderByPositionAsc(Long playlistId);

    void deleteByPlaylist_Id(Long playlistId);

    void deleteBySong_Id(Long songId);
}
