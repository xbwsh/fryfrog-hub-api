package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MusicTrackRepository extends JpaRepository<MusicTrack, Long> {

    List<MusicTrack> findByTitleContainingIgnoreCase(String title);

    List<MusicTrack> findByArtistContainingIgnoreCase(String artist);

    List<MusicTrack> findByAlbumContainingIgnoreCase(String album);

    Optional<MusicTrack> findByFilePath(String filePath);

    List<MusicTrack> findByFavoriteTrue();

    List<MusicTrack> findByLastPlayedAtIsNotNullOrderByLastPlayedAtDesc();

    List<MusicTrack> findByPlayCountGreaterThanOrderByPlayCountDesc(Integer playCount);

    @Query("SELECT DISTINCT t.artist FROM MusicTrack t WHERE t.artist IS NOT NULL AND t.artist != ''")
    List<String> findDistinctArtists();

    @Query("SELECT t FROM MusicTrack t WHERE t.playCount = 0 OR t.playCount IS NULL ORDER BY t.createdAt DESC")
    List<MusicTrack> findUnplayedTracks();

    @Query("SELECT t FROM MusicTrack t WHERE t.artist = :artist")
    List<MusicTrack> findByArtist(@Param("artist") String artist);

    @Query("SELECT t FROM MusicTrack t ORDER BY RANDOM() LIMIT :limit")
    List<MusicTrack> findRandom(@Param("limit") int limit);

    @Query("SELECT t FROM MusicTrack t WHERE t.genre = :genre")
    List<MusicTrack> findByGenre(@Param("genre") String genre);

    @Query("SELECT DISTINCT t.genre FROM MusicTrack t WHERE t.genre IS NOT NULL AND t.genre != ''")
    List<String> findDistinctGenres();
}
