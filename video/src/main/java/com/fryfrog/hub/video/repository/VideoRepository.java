package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    List<Video> findByTitleContainingIgnoreCase(String title);

    List<Video> findByDirectorContainingIgnoreCase(String director);

    List<Video> findByGenreContainingIgnoreCase(String genre);

    Optional<Video> findByFilePath(String filePath);

    List<Video> findByFavoriteTrue();

    List<Video> findByTmdbIdIsNull();

    Optional<Video> findByTmdbId(Long tmdbId);

    List<Video> findByMediaType(String mediaType);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series.id = :seriesId")
    long countBySeriesId(@Param("seriesId") Long seriesId);

    Optional<Video> findByFileName(String fileName);

    List<Video> findBySeries(com.fryfrog.hub.video.model.VideoSeries series);

    List<Video> findByFilePathContaining(String path);

    @Query("SELECT v FROM Video v WHERE v.filePath LIKE :dirPattern AND v.tmdbId IS NULL")
    List<Video> findUnboundInDirectory(@Param("dirPattern") String dirPattern);
}
