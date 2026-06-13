package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MusicTrackRepository extends JpaRepository<MusicTrack, Long> {

    List<MusicTrack> findByTitleContainingIgnoreCase(String title);

    List<MusicTrack> findByArtistContainingIgnoreCase(String artist);

    List<MusicTrack> findByAlbumContainingIgnoreCase(String album);

    List<MusicTrack> findByFilePath(String filePath);
}
