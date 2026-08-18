package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicAlbum;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MusicAlbumRepository extends JpaRepository<MusicAlbum, Long> {

    Optional<MusicAlbum> findFirstByTitleAndArtistNameAndLibraryId(String title, String artistName, Long libraryId);

    List<MusicAlbum> findByLibraryIdInOrderByTitleAsc(Collection<Long> libraryIds);

    List<MusicAlbum> findByArtist_IdOrderByYearAsc(Long artistId);

    List<MusicAlbum> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicAlbum> findByLibraryIdInAndArtistNameContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicAlbum> findByLibraryIdInAndGenreIgnoreCase(Collection<Long> libraryIds, String genre);

    List<MusicAlbum> findByLibraryIdInAndYearBetweenOrderByYearDesc(Collection<Long> libraryIds, int from, int to);

    List<MusicAlbum> findByLibraryIdInOrderByYearDesc(Collection<Long> libraryIds, Pageable pageable);

    List<MusicAlbum> findByLibraryIdInOrderByTitleAsc(Collection<Long> libraryIds, Pageable pageable);

    long countByLibraryIdIn(Collection<Long> libraryIds);
}
