package com.fryfrog.hub.music.repository;

import com.fryfrog.hub.music.model.MusicAlbum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MusicAlbumRepository extends JpaRepository<MusicAlbum, Long> {

    Optional<MusicAlbum> findFirstByTitleAndArtistNameAndLibraryId(String title, String artistName, Long libraryId);

    List<MusicAlbum> findByLibraryIdInOrderByTitleAsc(Collection<Long> libraryIds);

    Page<MusicAlbum> findByLibraryIdIn(Collection<Long> libraryIds, Pageable pageable);

    List<MusicAlbum> findByArtist_IdOrderByYearAsc(Long artistId);

    List<MusicAlbum> findByLibraryIdInAndTitleContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicAlbum> findByLibraryIdInAndArtistNameContainingIgnoreCase(Collection<Long> libraryIds, String query);

    List<MusicAlbum> findByLibraryIdInAndGenreIgnoreCase(Collection<Long> libraryIds, String genre);

    List<MusicAlbum> findByLibraryIdInAndYearBetweenOrderByYearDesc(Collection<Long> libraryIds, int from, int to);

    List<MusicAlbum> findByLibraryIdInOrderByYearDesc(Collection<Long> libraryIds, Pageable pageable);

    List<MusicAlbum> findByLibraryIdInOrderByTitleAsc(Collection<Long> libraryIds, Pageable pageable);

    long countByLibraryIdIn(Collection<Long> libraryIds);

    /** 指定库集合中每个歌手的专辑数，一次查询返回（避免列表页 N+1）。 */
    @Query("SELECT a.artist.id AS artistId, COUNT(a) AS cnt FROM MusicAlbum a " +
           "WHERE a.artist.id IN :artistIds AND a.libraryId IN :libraryIds GROUP BY a.artist.id")
    List<Map<String, Object>> countByArtistIds(@Param("artistIds") Collection<Long> artistIds,
                                               @Param("libraryIds") Collection<Long> libraryIds);
}
