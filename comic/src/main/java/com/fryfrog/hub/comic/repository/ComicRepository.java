package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.Comic;
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
public interface ComicRepository extends JpaRepository<Comic, Long> {

    List<Comic> findByTitleContainingIgnoreCase(String title);

    Page<Comic> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    List<Comic> findByAuthorContainingIgnoreCase(String author);

    Page<Comic> findByAuthorContainingIgnoreCase(String author, Pageable pageable);

    Optional<Comic> findByFilePath(String filePath);

    List<Comic> findByFavoriteTrue();

    Page<Comic> findByFavoriteTrue(Pageable pageable);

    List<Comic> findByMetadataSourceIdIsNullOrderByVolumeAsc();

    @Query("SELECT c FROM Comic c WHERE c.metadataSourceId IS NULL AND (c.scrapeAttemptedAt IS NULL OR c.scrapeAttemptedAt < :cutoff) ORDER BY c.volume ASC")
    List<Comic> findUnscrapedAfterCutoff(@Param("cutoff") LocalDateTime cutoff);

    List<Comic> findBySeriesRef_Id(Long seriesId);

    @Query(value = "SELECT * FROM comics WHERE series_id IS NULL AND series IS NOT NULL AND series <> ''", nativeQuery = true)
    List<Comic> findUnboundWithSeriesName();
}
