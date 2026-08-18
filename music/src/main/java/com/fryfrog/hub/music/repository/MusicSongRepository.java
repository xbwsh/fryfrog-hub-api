package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicSong;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MusicSongRepository extends JpaRepository<MusicSong, Long> {

    Optional<MusicSong> findByFilePath(String filePath);

    List<MusicSong> findByAlbum_IdOrderByDiscNumberAscTrackNumberAsc(Long albumId);

    List<MusicSong> findByArtist_IdOrderByAlbumNameAsc(Long artistId);

    List<MusicSong> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicSong> findByLibraryIdInAndArtistNameContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicSong> findByLibraryIdInAndAlbumNameContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicSong> findByLibraryIdInAndGenreIgnoreCase(Collection<Long> libraryIds, String genre);

    List<MusicSong> findByLibraryIdIn(Collection<Long> libraryIds, org.springframework.data.domain.Pageable pageable);

    List<MusicSong> findByLibraryIdIn(Collection<Long> libraryIds);

    long countByLibraryIdIn(Collection<Long> libraryIds);
}
