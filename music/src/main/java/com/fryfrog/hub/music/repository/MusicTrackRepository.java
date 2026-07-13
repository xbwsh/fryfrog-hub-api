package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicTrack;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MusicTrackRepository extends JpaRepository<MusicTrack, Long> {

    List<MusicTrack> findByTitleContainingIgnoreCase(String title);

    Page<MusicTrack> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    List<MusicTrack> findByArtistContainingIgnoreCase(String artist);

    Page<MusicTrack> findByArtistContainingIgnoreCase(String artist, Pageable pageable);

    List<MusicTrack> findByAlbumContainingIgnoreCase(String album);

    Optional<MusicTrack> findByFilePath(String filePath);

    List<MusicTrack> findByFavoriteTrue();

    Page<MusicTrack> findByFavoriteTrue(Pageable pageable);

    List<MusicTrack> findByLastPlayedAtIsNotNullOrderByLastPlayedAtDesc();

    Page<MusicTrack> findByLastPlayedAtIsNotNullOrderByLastPlayedAtDesc(Pageable pageable);

    List<MusicTrack> findByPlayCountGreaterThanOrderByPlayCountDesc(Integer playCount);

    Page<MusicTrack> findByPlayCountGreaterThanOrderByPlayCountDesc(Integer playCount, Pageable pageable);

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

    @Query(value = "SELECT DISTINCT COALESCE(artist, '') as grp, COALESCE(album, '') as alb FROM music_tracks WHERE album IS NOT NULL AND album != '' ORDER BY grp, alb",
            countQuery = "SELECT COUNT(DISTINCT COALESCE(artist, '') || '|' || COALESCE(album, '')) FROM music_tracks WHERE album IS NOT NULL AND album != ''",
            nativeQuery = true)
    Page<Object[]> findDistinctAlbumGroups(Pageable pageable);

    @Query(value = "SELECT * FROM music_tracks WHERE COALESCE(artist, '') = COALESCE(:artist, '') AND COALESCE(album, '') = COALESCE(:album, '') ORDER BY disc_number, track_number",
            nativeQuery = true)
    List<MusicTrack> findByArtistAndAlbum(@Param("artist") String artist, @Param("album") String album);
}
