package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.VideoSeries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoSeriesRepository extends JpaRepository<VideoSeries, Long> {

    List<VideoSeries> findByTitleContainingIgnoreCase(String title);

    Optional<VideoSeries> findByTmdbId(Long tmdbId);

    Optional<VideoSeries> findByTitle(String title);

    @Query("SELECT s.tmdbId FROM VideoSeries s WHERE s.tmdbId IS NOT NULL GROUP BY s.tmdbId HAVING COUNT(s) > 1")
    List<Long> findDuplicateTmdbIds();

    /** 某资源库下的系列（系列内至少一集属于该库），按标题排序分页。 */
    @Query("SELECT DISTINCT s FROM VideoSeries s JOIN s.videos v WHERE v.libraryId = :libraryId ORDER BY s.title")
    Page<VideoSeries> findByLibraryId(@Param("libraryId") Long libraryId, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT s.id) FROM VideoSeries s JOIN s.videos v WHERE v.libraryId = :libraryId")
    long countByLibraryId(@Param("libraryId") Long libraryId);

    /** 游离系列（无任何集，或所有集的 libraryId 为空）。 */
    @Query("SELECT s FROM VideoSeries s WHERE NOT EXISTS " +
           "(SELECT v FROM Video v WHERE v.series = s AND v.libraryId IS NOT NULL) ORDER BY s.title")
    Page<VideoSeries> findUnassigned(Pageable pageable);

    @Query("SELECT COUNT(s) FROM VideoSeries s WHERE NOT EXISTS " +
           "(SELECT v FROM Video v WHERE v.series = s AND v.libraryId IS NOT NULL)")
    long countUnassigned();
}
