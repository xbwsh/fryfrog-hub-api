package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    List<Video> findByTitleContainingIgnoreCase(String title);

    Page<Video> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    List<Video> findByDirectorContainingIgnoreCase(String director);

    Page<Video> findByDirectorContainingIgnoreCase(String director, Pageable pageable);

    List<Video> findByGenreContainingIgnoreCase(String genre);

    Optional<Video> findByFilePath(String filePath);

    List<Video> findByFavoriteTrue();

    Page<Video> findByFavoriteTrue(Pageable pageable);

    List<Video> findByTmdbIdIsNull();

    @Query("SELECT v FROM Video v WHERE (v.tmdbId IS NULL OR v.metadataUpdatedAt IS NULL)")
    List<Video> findUnscrapedVideos();

    Optional<Video> findByTmdbId(Long tmdbId);

    List<Video> findAllByTmdbId(Long tmdbId);

    List<Video> findByMediaType(String mediaType);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series.id = :seriesId")
    long countBySeriesId(@Param("seriesId") Long seriesId);

    Optional<Video> findByFileName(String fileName);

    List<Video> findBySeries(com.fryfrog.hub.video.model.VideoSeries series);

    List<Video> findByFilePathContaining(String path);

    List<Video> findBySeriesIsNullOrderByTitleAsc();
}
