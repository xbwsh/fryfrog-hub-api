package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.VideoSeries;
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
}
