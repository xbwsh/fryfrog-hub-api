package com.fryfrog.hub.common.repository;

import com.fryfrog.hub.common.model.MediaSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaSeriesRepository extends JpaRepository<MediaSeries, Long> {

    Optional<MediaSeries> findByTitleAndMediaType(String title, String mediaType);

    List<MediaSeries> findByMediaType(String mediaType);

    List<MediaSeries> findByTitleContainingIgnoreCase(String title);

    @Query("SELECT ms FROM MediaSeries ms WHERE LOWER(ms.title) = LOWER(:title) AND ms.mediaType = :type")
    Optional<MediaSeries> findByTitleAndMediaTypeIgnoreCase(@Param("title") String title, @Param("type") String type);
}
